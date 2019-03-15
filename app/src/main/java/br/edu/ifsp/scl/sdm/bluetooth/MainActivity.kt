package br.edu.ifsp.scl.sdm.bluetooth

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v4.app.ActivityCompat
import android.support.v4.content.PermissionChecker
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.Constantes.ATIVA_BLUETOOTH
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.Constantes.ATIVA_DESCOBERTA_BLUETOOTH
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.Constantes.MENSAGEM_DESCONEXAO
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.Constantes.MENSAGEM_TEXTO
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.Constantes.REQUER_PERMISSOES_LOCALIZACAO
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.Constantes.TEMPO_DESCOBERTA_SERVICO_BLUETOOTH
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.adaptadorBt
import br.edu.ifsp.scl.sdm.bluetooth.BluetoothSingleton.outputStream
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException

class MainActivity : AppCompatActivity() {
    companion object {
        private val EXTRA_MODO_CLIENTE = "MainActivity.ModoCliente"
        private val EXTRA_NOME_USUARIO = "MainActivity.NomeUsuario"
    }

    private var threadServidor: ThreadServidor? = null
    private var threadCliente: ThreadCliente? = null
    private var threadComunicacao: ThreadComunicacao? = null

    var listaBtsEncontrados: MutableList<BluetoothDevice>? = null

    private var eventosBtReceiver: EventosBluetoothReceiver? = null

    private var historicoAdapter: ArrayAdapter<String>? = null

    var mHandler: TelaPrincipalHandler? = null

    private var aguardeDialog: ProgressDialog? = null

    private var modoCliente: Boolean? = null
    private var nomeUsuario: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historicoAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        historicoListView.adapter = historicoAdapter

        mHandler = TelaPrincipalHandler()

        pegandoAdaptadorBt()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUER_PERMISSOES_LOCALIZACAO)
        }

        if (savedInstanceState?.containsKey(EXTRA_MODO_CLIENTE) == true) {
            nomeUsuario = savedInstanceState.getString(EXTRA_NOME_USUARIO)
            if (savedInstanceState.getBoolean(EXTRA_MODO_CLIENTE)) {
                iniciarModoCliente()
            } else {
                iniciarModoServidor()
            }
        } else {
            exibirEscolhaModo()
        }
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)

        modoCliente?.let { outState?.putBoolean(EXTRA_MODO_CLIENTE, it) }
        nomeUsuario?.let { outState?.putString(EXTRA_NOME_USUARIO, it) }
    }

    private fun iniciarModoCliente() {
        toast("Configurando modo cliente")
        modoCliente = true;

        listaBtsEncontrados = mutableListOf()

        registraReceiver()

        adaptadorBt?.startDiscovery()

        exibirAguardeDialog("Procurando dispositivos Bluetooth", 0)
    }

    private fun iniciarModoServidor() {
        toast("Configurando modo servidor")
        modoCliente = false

        val descobertaIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        descobertaIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, TEMPO_DESCOBERTA_SERVICO_BLUETOOTH)
        startActivityForResult(descobertaIntent, ATIVA_DESCOBERTA_BLUETOOTH)
    }

    private fun registraReceiver() {
        eventosBtReceiver = eventosBtReceiver?: EventosBluetoothReceiver(this)

        registerReceiver(eventosBtReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        registerReceiver(eventosBtReceiver, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
    }

    fun desregistrarReceiver() {
        eventosBtReceiver?.let { unregisterReceiver(it) }
        eventosBtReceiver = null
    }

    private fun exibirAguardeDialog(mensagem: String, tempo: Int) {
        aguardeDialog = ProgressDialog.show(this, "Aguarde", mensagem, true, true) {
            onCancelDialog(it)
        }
        aguardeDialog?.show()

        if (tempo > 0) {
            mHandler?.postDelayed({
                if (threadComunicacao == null) {
                    aguardeDialog?.dismiss()
                }
            }, tempo * 1000L)
        }
    }

    private fun onCancelDialog(dialogInterface: DialogInterface) {
        adaptadorBt?.cancelDiscovery()

        paraThreadsFilhas()
    }

    private fun paraThreadsFilhas() {
        if (threadComunicacao != null) {
            threadComunicacao?.parar()
            threadComunicacao = null
        }
        if (threadCliente != null) {
            threadCliente?.parar()
            threadCliente = null
        }
        if (threadServidor != null) {
            threadServidor?.parar()
            threadServidor = null
        }
    }

    private fun pegandoAdaptadorBt() {
        adaptadorBt = BluetoothAdapter.getDefaultAdapter()

        if (adaptadorBt != null) {
            if (!adaptadorBt!!.isEnabled) {
                val ativaBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(ativaBluetoothIntent, ATIVA_BLUETOOTH)
            }
        } else {
            toast("Adaptador Bt não disponível")
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUER_PERMISSOES_LOCALIZACAO) {
            if (!grantResults.all { it == PermissionChecker.PERMISSION_GRANTED }) {
                toast("Permissões são necessárias")
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == ATIVA_BLUETOOTH) {
            if (resultCode != Activity.RESULT_OK) {
                toast("Bluetooth necessário")
                finish()
            }
        } else if (requestCode == ATIVA_DESCOBERTA_BLUETOOTH) {
            if (resultCode == Activity.RESULT_CANCELED) {

                toast("Visibilidade necessária")
                finish()
            } else {
                iniciaThreadServidor()
            }
        }
    }

    override fun onDestroy() {
        desregistrarReceiver()
        paraThreadsFilhas()
        super.onDestroy()
    }

    private fun iniciaThreadServidor() {
        paraThreadsFilhas()

        exibirAguardeDialog("Aguardando conexões", TEMPO_DESCOBERTA_SERVICO_BLUETOOTH)

        threadServidor = ThreadServidor(this)
        threadServidor?.iniciar()
    }

    private fun iniciaThreadCliente(i: Int) {
        paraThreadsFilhas()

        threadCliente = ThreadCliente(this)
        threadCliente?.iniciar(listaBtsEncontrados?.get(i))
    }

    fun exibirDispositivosEncontrados() {
        aguardeDialog?.dismiss()

        val listaNomesBtsEncontrados: MutableList<String> = mutableListOf()
        listaBtsEncontrados?.forEach { listaNomesBtsEncontrados.add(it.name ?: "Sem nome") }

        val escolhaDispositivoDialog = with(AlertDialog.Builder(this)) {
            setTitle("Dispositivos encontrados")
            setSingleChoiceItems(listaNomesBtsEncontrados.toTypedArray(), -1, this@MainActivity::trataSelecaoServidor)
        }

        escolhaDispositivoDialog.show()
    }

    private fun exibirEscolhaModo() {
        val editText = EditText(this)
        editText.hint = "Nome de usuário"

        val escolhaModoDialog = with(AlertDialog.Builder(this)) {
            setTitle("Modo")
            setView(editText)
            setCancelable(false)
            setPositiveButton("Cliente") { dialog, which ->
                iniciarModoCliente()
            }
            setNegativeButton("Servidor") { dialog, which ->
                iniciarModoServidor()
            }
            setOnDismissListener { nomeUsuario = editText.text.toString() }

        }
        escolhaModoDialog.show()
    }

    private fun trataSelecaoServidor(dialog: DialogInterface, which: Int) {
        iniciaThreadCliente(which)

        adaptadorBt?.cancelDiscovery()

        dialog.dismiss()
    }

    fun enviarMensagem(view: View) {
        if (view == enviarBt) {
            val mensagem = mensagemEditText.text.toString()
            mensagemEditText.text = null

            try {
                if (outputStream != null) {
                    outputStream?.writeUTF(Mensagem(mensagem, nomeUsuario).toJSON())

                    historicoAdapter?.add("Eu: $mensagem")
                    historicoAdapter?.notifyDataSetChanged()
                }
            } catch (e: IOException) {
                mHandler?.obtainMessage(MENSAGEM_DESCONEXAO, e.message + "[0]")?.sendToTarget()

                e.printStackTrace()
            }

        }
    }

    fun trataSocket(socket: BluetoothSocket?) {
        aguardeDialog?.dismiss()
        threadComunicacao = ThreadComunicacao(this)
        threadComunicacao?.iniciar(socket)
    }

    private fun toast(mensagem: String) {
        Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show()
    }

    inner class TelaPrincipalHandler : Handler() {

        override fun handleMessage(msg: Message?) {
            super.handleMessage(msg)

            if (msg?.what == MENSAGEM_TEXTO) {
                historicoAdapter?.add(msg.obj.toString())
                historicoAdapter?.notifyDataSetChanged()
            } else if (msg?.what == MENSAGEM_DESCONEXAO) {
                toast("Desconectou: ${msg.obj}")
            }
        }

    }
}
