package net.spin.tachiyomi.legacy

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

class PinDialog(private val context: Context) {
    
    interface OnPinEnteredListener {
        fun onPinCorrect(pin: String)
        fun onPinIncorrect()
        fun onSetupComplete(pin: String)
    }
    
    private var listener: OnPinEnteredListener? = null
    private var isSetupMode = false
    
    fun setListener(listener: OnPinEnteredListener) = apply {
        this.listener = listener
        this
    }
    
    fun setSetupMode(isSetup: Boolean) = apply {
        this.isSetupMode = isSetup
        this
    }
    
    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin, null)
        
        val titleText = view.findViewById<TextView>(R.id.titleText)
        val hintText = view.findViewById<TextView>(R.id.hintText)
        val pinDisplay = view.findViewById<TextView>(R.id.pinDisplay)
        val errorText = view.findViewById<TextView>(R.id.errorText)
        val pinInput = view.findViewById<EditText>(R.id.pinInput)
        val btnBackspace = view.findViewById<ImageButton>(R.id.btnBackspace)
        
        val btn1 = view.findViewById<Button>(R.id.btn1)
        val btn2 = view.findViewById<Button>(R.id.btn2)
        val btn3 = view.findViewById<Button>(R.id.btn3)
        val btn4 = view.findViewById<Button>(R.id.btn4)
        val btn5 = view.findViewById<Button>(R.id.btn5)
        val btn6 = view.findViewById<Button>(R.id.btn6)
        val btn7 = view.findViewById<Button>(R.id.btn7)
        val btn8 = view.findViewById<Button>(R.id.btn8)
        val btn9 = view.findViewById<Button>(R.id.btn9)
        val btn0 = view.findViewById<Button>(R.id.btn0)
        
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        
        titleText.text = if (isSetupMode) "Configurar PIN de acceso" else "Ingrese PIN"
        hintText.text = if (isSetupMode) {
            "Cree un PIN de 4 dígitos"
        } else {
            "Ingrese su PIN de 4 dígitos"
        }
        
        var currentPin = ""
        
        fun updateDisplay() {
            pinDisplay.text = "•".repeat(currentPin.length)
        }
        
        fun onDigitPressed(digit: String) {
            if (currentPin.length < 4) {
                currentPin += digit
                updateDisplay()
                errorText.visibility = View.GONE
                
                if (currentPin.length == 4) {
                    if (isSetupMode) {
                        listener?.onSetupComplete(currentPin)
                        dialog.dismiss()
                    } else {
                        if (PrivateLibraryManager.verifyPin(currentPin)) {
                            listener?.onPinCorrect(currentPin)
                            dialog.dismiss()
                        } else {
                            listener?.onPinIncorrect()
                            errorText.visibility = View.VISIBLE
                            currentPin = ""
                            updateDisplay()
                        }
                    }
                }
            }
        }
        
        fun onBackspace() {
            if (currentPin.isNotEmpty()) {
                currentPin = currentPin.dropLast(1)
                updateDisplay()
                errorText.visibility = View.GONE
            }
        }
        
        // Listeners explícitos para cada botón (sin forEachIndexed)
        btn1.setOnClickListener { onDigitPressed("1") }
        btn2.setOnClickListener { onDigitPressed("2") }
        btn3.setOnClickListener { onDigitPressed("3") }
        btn4.setOnClickListener { onDigitPressed("4") }
        btn5.setOnClickListener { onDigitPressed("5") }
        btn6.setOnClickListener { onDigitPressed("6") }
        btn7.setOnClickListener { onDigitPressed("7") }
        btn8.setOnClickListener { onDigitPressed("8") }
        btn9.setOnClickListener { onDigitPressed("9") }
        btn0.setOnClickListener { onDigitPressed("0") }
        btnBackspace.setOnClickListener { onBackspace() }
        
        // Permitir entrada directa desde teclado físico
        pinInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_0 -> onDigitPressed("0")
                    android.view.KeyEvent.KEYCODE_1 -> onDigitPressed("1")
                    android.view.KeyEvent.KEYCODE_2 -> onDigitPressed("2")
                    android.view.KeyEvent.KEYCODE_3 -> onDigitPressed("3")
                    android.view.KeyEvent.KEYCODE_4 -> onDigitPressed("4")
                    android.view.KeyEvent.KEYCODE_5 -> onDigitPressed("5")
                    android.view.KeyEvent.KEYCODE_6 -> onDigitPressed("6")
                    android.view.KeyEvent.KEYCODE_7 -> onDigitPressed("7")
                    android.view.KeyEvent.KEYCODE_8 -> onDigitPressed("8")
                    android.view.KeyEvent.KEYCODE_9 -> onDigitPressed("9")
                    android.view.KeyEvent.KEYCODE_DEL -> onBackspace()
                }
            }
            false
        }
        
        dialog.show()
    }
}
