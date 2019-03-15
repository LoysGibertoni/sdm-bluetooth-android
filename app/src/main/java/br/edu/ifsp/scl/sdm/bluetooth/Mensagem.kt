package br.edu.ifsp.scl.sdm.bluetooth

import org.json.JSONObject

data class Mensagem(val conteudo: String, val nomeUsuario: String? = null) {
    companion object {
        fun fromJSON(json: String?) : Mensagem? {
            json?.let {
                with(JSONObject(json)) {
                    return Mensagem(getString("conteudo"), getString("nomeUsuario"))
                }
            }
            return null
        }
    }

    fun toJSON() : String {
        with(JSONObject()) {
            put("conteudo", conteudo)
            put("nomeUsuario", nomeUsuario)
            return toString()
        }
    }
}