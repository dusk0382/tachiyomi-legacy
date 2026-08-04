package net.spin.tachiyomi.legacy

import android.graphics.RectF
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.atomic.AtomicLong

class PageAdapter(
    private val viewModel: ReaderViewModel,
    private val onTapListener: ZoomableImageView.OnTapListener
) : RecyclerView.Adapter<PageAdapter.PageVH>() {

    override fun getItemCount(): Int = viewModel.pageCount.value ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_page, parent, false)
        return PageVH(view)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(position)
    }

    inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ZoomableImageView = view.findViewById(R.id.pageImage)
        private val loader: ProgressBar = view.findViewById(R.id.pageLoader)
        private val error: TextView = view.findViewById(R.id.pageError)
        
        private var currentRequestId: Long = -1
        private val requestCounter = AtomicLong(0)
        private val hiResToken = AtomicLong(0)

        init {
            image.onTapListener = onTapListener
            image.onZoomRequestListener = { norm ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) {
                    viewModel.releaseHighRes()
                    image.clearHighResRegion()
                } else if (norm == null) {
                    viewModel.releaseHighRes()
                    image.clearHighResRegion()
                } else {
                    requestHighRes(pos, norm)
                }
            }
        }

        fun bind(position: Int) {
            currentRequestId = requestCounter.incrementAndGet()
            hiResToken.incrementAndGet()
            viewModel.releaseHighRes()
            image.clearHighResRegion()
            
            image.resetZoom()
            image.setImageDrawable(null)
            error.visibility = View.GONE
            loader.visibility = View.VISIBLE

            viewModel.loadPage(position, currentRequestId) { idx, bmp, responseId ->
                itemView.post {
                    if (responseId != currentRequestId) {
                        Log.d("MangaLite", "Callback descartado: request $responseId != current $currentRequestId")
                        return@post
                    }
                    
                    loader.visibility = View.GONE
                    
                    if (bmp != null && !bmp.isRecycled) {
                        image.setImageBitmap(bmp)
                        error.visibility = View.GONE
                        Log.d("MangaLite", "Página $idx mostrada (request $responseId)")
                    } else {
                        error.visibility = View.VISIBLE
                        error.text = itemView.context.getString(R.string.error_loading)
                        Log.w("MangaLite", "Página $idx falló (request $responseId)")
                    }
                }
            }
        }
        
        fun onViewRecycled() {
            currentRequestId = -1
            image.clearHighResRegion()
            image.resetZoom()
        }

        private fun requestHighRes(position: Int, norm: RectF) {
            val d = image.drawable ?: return
            val baseW = d.intrinsicWidth
            val baseH = d.intrinsicHeight
            if (baseW <= 0 || baseH <= 0) return

            val token = hiResToken.get()
            val viewportW = image.width.coerceAtLeast(100)
            val viewportH = image.height.coerceAtLeast(100)

            viewModel.requestHighResRegion(position, norm, viewportW, viewportH) { bmp, usedNorm ->
                image.post {
                    if (bindingAdapterPosition != position) {
                        bmp?.takeIf { !it.isRecycled }?.recycle()
                        return@post
                    }
                    if (token != hiResToken.get()) {
                        bmp?.takeIf { !it.isRecycled }?.recycle()
                        return@post
                    }
                    if (bmp == null) return@post

                    val region = RectF(
                        usedNorm.left * baseW,
                        usedNorm.top * baseH,
                        usedNorm.right * baseW,
                        usedNorm.bottom * baseH
                    )
                    image.showHighResRegion(bmp, region, usedNorm)
                }
            }
        }
    }
    
    override fun onViewRecycled(holder: PageVH) {
        super.onViewRecycled(holder)
        holder.onViewRecycled()
    }
}
