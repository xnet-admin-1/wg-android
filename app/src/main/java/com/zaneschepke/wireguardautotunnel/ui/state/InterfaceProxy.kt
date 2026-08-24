package com.zaneschepke.wireguardautotunnel.ui.state

import com.wireguard.config.Interface
import com.zaneschepke.wireguardautotunnel.util.extensions.ifNotBlank
import com.zaneschepke.wireguardautotunnel.util.extensions.joinAndTrim
import com.zaneschepke.wireguardautotunnel.util.extensions.toTrimmedList
import java.util.*

data class InterfaceProxy(
    val privateKey: String = "",
    val publicKey: String = "",
    val addresses: String = "",
    val dnsServers: String = "",
    val listenPort: String = "",
    val mtu: String = "",
    val includedApplications: Set<String> = emptySet(),
    val excludedApplications: Set<String> = emptySet(),
    val preUp: String = "",
    val postUp: String = "",
    val preDown: String = "",
    val postDown: String = "",
) {

    fun toWgInterface(): Interface {
        return Interface.Builder()
            .apply {
                parseAddresses(addresses)
                parsePrivateKey(privateKey)
                dnsServers.ifNotBlank { parseDnsServers(it) }
                listenPort.ifNotBlank { parseListenPort(it) }
                mtu.ifNotBlank { parseMtu(it) }
                includeApplications(includedApplications)
                excludeApplications(excludedApplications)
                preUp.toTrimmedList().forEach { parsePreUp(it) }
                postUp.toTrimmedList().forEach { parsePostUp(it) }
                preDown.toTrimmedList().forEach { parsePreDown(it) }
                postDown.toTrimmedList().forEach { parsePostDown(it) }
            }
            .build()
    }

    fun getValidationErrors(): List<String> {
        val errors = mutableListOf<String>()

        if (privateKey.isBlank()) {
            errors.add("Private key is required")
        } else if (!isValidBase64(privateKey) || privateKey.length != 44) {
            errors.add("Invalid private key format (must be 44-character Base64)")
        }

        if (addresses.isBlank()) {
            errors.add("Addresses are required")
        }

        listenPort.ifNotBlank {
            val port = it.toIntOrNull()
            if (port == null) errors.add("Listen port must be an integer")
            else if (port !in 1..65535) errors.add("Listen port must be between 1 and 65535")
        }

        mtu.ifNotBlank {
            val mtuValue = it.toIntOrNull()
            if (mtuValue == null) errors.add("MTU must be an integer")
            else if (mtuValue !in 576..9200) errors.add("MTU should be between 576 and 9200")
        }

        return errors
    }

    private fun isValidBase64(str: String): Boolean {
        return try {
            Base64.getDecoder().decode(str)
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun from(i: Interface): InterfaceProxy {
            val dnsString =
                listOf(
                        i.dnsServers.joinToString(", ").replace("/", "").trim(),
                        i.dnsSearchDomains.joinAndTrim(),
                    )
                    .filter { it.isNotEmpty() }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            return InterfaceProxy(
                publicKey = i.keyPair.publicKey.toBase64().trim(),
                privateKey = i.keyPair.privateKey.toBase64().trim(),
                addresses = i.addresses.joinToString(", ").trim(),
                dnsServers = dnsString ?: "",
                listenPort =
                    if (i.listenPort.isPresent) i.listenPort.get().toString().trim() else "",
                mtu = if (i.mtu.isPresent) i.mtu.get().toString().trim() else "",
                includedApplications = i.includedApplications.toMutableSet(),
                excludedApplications = i.excludedApplications.toMutableSet(),
                preUp = i.preUp.joinAndTrim(),
                postUp = i.postUp.joinAndTrim(),
                preDown = i.preDown.joinAndTrim(),
                postDown = i.postDown.joinAndTrim(),
            )
        }
    }
}
