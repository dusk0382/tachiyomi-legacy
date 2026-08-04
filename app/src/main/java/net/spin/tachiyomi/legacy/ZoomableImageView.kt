package net.spin.tachiyomi.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var minScale = 1f
    private var maxScale = 3f
    private var currentScale = 1f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = INVALID_POINTER_ID

    // Para pan durante zoom
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var isZooming = false
    private var wasZooming = false
    private var isInitialized = false

    var onTapListener: OnTapListener? = null

    // Alta resolución bajo demanda
    private val handler = Handler(Looper.getMainLooper())

    private var hiResBmp: Bitmap? = null
    private var hiResRegion: RectF? = null
    private var lastNormRegion: RectF? = null

    /** Listener notificado tras cada gesto de zoom/pan (con debounce). null => zoom-out. */
    var onZoomRequestListener: ((RectF?) -> Unit)? = null

    private val sharpRunnable = Runnable {
        if (currentScale > minScale * 1.4f) {
            val rect = getVisibleNormalizedRect()
            if (rect != null && shouldReRequest(rect)) {
                onZoomRequestListener?.invoke(rect)
            }
        } else {
            onZoomRequestListener?.invoke(null)
        }
    }

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isZooming = true
                wasZooming = false
                // Guardar posición inicial del foco
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var scaleFactor = detector.scaleFactor
                val newScale = currentScale * scaleFactor

                if (newScale < minScale) {
                    scaleFactor = minScale / currentScale
                } else if (newScale > maxScale) {
                    scaleFactor = maxScale / currentScale
                }

                // Aplicar zoom en el foco actual
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                currentScale *= scaleFactor

                // Calcular delta del foco para pan simultáneo
                val dx = detector.focusX - lastFocusX
                val dy = detector.focusY - lastFocusY
                
                // Aplicar pan
                matrix.postTranslate(dx, dy)
                
                // Actualizar foco para próximo frame
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY

                constrainTranslation()
                imageMatrix = matrix
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isZooming = false
                wasZooming = true
                post { wasZooming = false }
                scheduleSharpRequest()
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale <= minScale * 1.1f) {
                    val targetScale = minScale * 2f
                    val scaleFactor = targetScale / currentScale
                    matrix.postScale(scaleFactor, scaleFactor, e.x, e.y)
                    currentScale = targetScale
                    constrainTranslation()
                    imageMatrix = matrix
                } else {
                    resetZoom()
                }
                scheduleSharpRequest()
                return true
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            post { fitImageToView() }
        }
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        clearHighResRegion()
        if (drawable != null && width > 0 && height > 0) {
            post { fitImageToView() }
        }
    }

    private fun fitImageToView() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val imgWidth = d.intrinsicWidth.toFloat()
        val imgHeight = d.intrinsicHeight.toFloat()
        if (imgWidth <= 0f || imgHeight <= 0f) return

        val scaleX = viewWidth / imgWidth
        val scaleY = viewHeight / imgHeight
        val baseScale = min(scaleX, scaleY)

        minScale = baseScale
        maxScale = baseScale * 3f
        currentScale = baseScale

        matrix.reset()
        matrix.postScale(baseScale, baseScale)

        val transX = (viewWidth - imgWidth * baseScale) / 2f
        val transY = (viewHeight - imgHeight * baseScale) / 2f
        matrix.postTranslate(transX, transY)

        imageMatrix = matrix
        isInitialized = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInitialized) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (isZooming || wasZooming) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                
                if (currentScale > minScale * 1.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (currentScale > minScale * 1.01f && 
                    event.pointerCount == 1 && 
                    activePointerId != INVALID_POINTER_ID) {
                    
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        
                        matrix.postTranslate(dx, dy)
                        constrainTranslation()
                        imageMatrix = matrix
                        
                        lastTouchX = x
                        lastTouchY = y
                        
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleSharpRequest()
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > 1) {
                    activePointerId = INVALID_POINTER_ID
                }
            }
            
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < event.pointerCount) {
                        lastTouchX = event.getX(newPointerIndex)
                        lastTouchY = event.getY(newPointerIndex)
                        activePointerId = event.getPointerId(newPointerIndex)
                    } else {
                        activePointerId = INVALID_POINTER_ID
                    }
                }
            }
        }
        
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (currentScale > minScale * 1.05f) return
        
        val width = width.toFloat()
        if (width <= 0f) return
        
        val third = width / 3f
        when {
            x < third -> onTapListener?.onTapLeft()
            x > width - third -> onTapListener?.onTapRight()
            else -> onTapListener?.onTapCenter()
        }
    }

    private fun constrainTranslation() {
        matrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val d = drawable ?: return
        val scaledWidth = d.intrinsicWidth * currentScale
        val scaledHeight = d.intrinsicHeight * currentScale
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        var fixTransX = 0f
        var fixTransY = 0f

        if (scaledWidth < viewWidth) {
            fixTransX = (viewWidth - scaledWidth) / 2f - transX
        } else {
            val maxTransX = 0f
            val minTransX = viewWidth - scaledWidth
            if (transX > maxTransX) fixTransX = maxTransX - transX
            else if (transX < minTransX) fixTransX = minTransX - transX
        }

        if (scaledHeight < viewHeight) {
            fixTransY = (viewHeight - scaledHeight) / 2f - transY
        } else {
            val maxTransY = 0f
            val minTransY = viewHeight - scaledHeight
            if (transY > maxTransY) fixTransY = maxTransY - transY
            else if (transY < minTransY) fixTransY = minTransY - transY
        }

        if (fixTransX != 0f || fixTransY != 0f) {
            matrix.postTranslate(fixTransX, fixTransY)
        }
    }

    fun resetZoom() {
        if (isInitialized) {
            fitImageToView()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val hb = hiResBmp ?: return
        val hr = hiResRegion ?: return
        if (hb.isRecycled) return

        canvas.save()
        canvas.concat(imageMatrix)

        val sx = hr.width() / hb.width.toFloat()
        val sy = hr.height() / hb.height.toFloat()
        canvas.translate(hr.left, hr.top)
        canvas.scale(sx, sy)
        canvas.drawBitmap(hb, 0f, 0f, null)

        canvas.restore()
    }

    /** Rectángulo visible en coordenadas normalizadas 0..1 de la imagen (base). */
    fun getVisibleNormalizedRect(): RectF? {
        val d = drawable ?: return null
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f) return null

        val inv = Matrix()
        imageMatrix.invert(inv)

        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(r)

        r.left = r.left.coerceIn(0f, bw)
        r.top = r.top.coerceIn(0f, bh)
        r.right = r.right.coerceIn(0f, bw)
        r.bottom = r.bottom.coerceIn(0f, bh)

        if (r.right <= r.left || r.bottom <= r.top) return null

        return RectF(r.left / bw, r.top / bh, r.right / bw, r.bottom / bh)
    }

    /** Muestra el bitmmap de alta resolución alineado a [regionBase] (coords de imagen base). */
    fun showHighResRegion(bmp: Bitmap?, regionBase: RectF, normalized: RectF) {
        hiResBmp = bmp
        hiResRegion = if (bmp != null) regionBase else null
        lastNormRegion = if (bmp != null) normalized else null
        invalidate()
    }

    /** Limpia el overlay de alta resolución. */
    fun clearHighResRegion() {
        handler.removeCallbacks(sharpRunnable)
        hiResBmp = null
        hiResRegion = null
        lastNormRegion = null
    }

    private fun shouldReRequest(visible: RectF): Boolean {
        val r = lastNormRegion ?: return true
        val growX = r.width() * 0.1f
        val growY = r.height() * 0.1f
        val expanded = RectF(
            r.left - growX,
            r.top - growY,
            r.right + growX,
            r.bottom + growY
        )
        return !expanded.contains(visible)
    }

    private fun scheduleSharpRequest() {
        handler.removeCallbacks(sharpRunnable)
        handler.postDelayed(sharpRunnable, ZOOM_DEBOUNCE_MS)
    }

    interface OnTapListener {
        fun onTapLeft()
        fun onTapRight()
        fun onTapCenter()
    }

    companion object {
        private const val INVALID_POINTER_ID = -1
        private const val ZOOM_DEBOUNCE_MS = 250L
    }
}
