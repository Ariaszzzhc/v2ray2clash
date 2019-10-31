package com.hiarias

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.typesafe.config.Optional
import io.ktor.client.*
import io.ktor.client.request.get
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.parse
import java.io.File
import java.util.*
import kotlin.system.exitProcess

@Serializable
data class VMess(
    val v: String,
    val host: String,
    val path: String,
    val tls: String,
    val ps: String,
    val add: String,
    val port: String,
    val id: String,
    val aid: String,
    val net: String,
    val type: String,
    @SerialName("inside_port")
    val insidePort: String? = null,
    @SerialName("")
    val unknown: String? = null
)

@Serializable
data class ClashVMess(
    val name: String,
    val type: String,
    val server: String,
    val port: String,
    val uuid: String,
    val alterId: Int,
    val cipher: String,
    val tls: Boolean,
    val network: String?,
    val wsPath: String?,
    val wsHeaders: Map<String, String>,
    val skipCertVerify: Boolean
)

@Serializable
data class ClashConfig(
    val port: Int,
    @SerialName("socks-port")
    val socksPort: Int,
    @SerialName("redir-port")
    val redirPort: Int,
    @SerialName("allow-lan")
    val allowLan: Boolean,
    @SerialName("bind-address")
    val bindAddress: String,
    val mode: String,
    @SerialName("log-level")
    val logLevel: String,
    @SerialName("external-controller")
    val externalController: String,
    val secret: String,
    @SerialName("external-ui")
    val externalUI: String,
    val hosts: Map<String, String> = emptyMap(),
    val dns: DNS,
    @SerialName("Proxy")
    val proxy: List<ClashVMess> = emptyList(),
    @SerialName("Proxy Group")
    val proxyGroup: List<ProxyGroup>,
    @SerialName("Rule")
    val rule: List<String>
)

@Serializable
data class DNS(
    val enable: Boolean,
    val ipv6: Boolean,
    val listen: String,
    @SerialName("enhanced-mode")
    val enhancedMode: String,
    @SerialName("nameserver")
    val nameServer: List<String>
)

@Serializable
data class ProxyGroup(
    val name: String,
    val type: String,
    val proxies: List<String>,
    @Optional val url: String? = null,
    @Optional val interval: Int? = null
)

class V2ray2Clash : CliktCommand() {
    val subUrl: String by option(help = "subscription url").prompt("订阅地址")

    override fun run() {
        if (subUrl.startsWith("https://") or subUrl.startsWith("http://")) {
            // Loading Template

            runBlocking {
                val clashVMesses = readSubscription(subUrl).filter {
                    it.startsWith("vmess://")
                }.map {
                    val decoder = Base64.getDecoder()
                    val json = Json(JsonConfiguration.Stable)
                    val body = decoder.decode(it.removePrefix("vmess://")).toString(Charsets.UTF_8)

                    json.parse(VMess.serializer(), body).let { vmess ->
                        ClashVMess(
                            name = vmess.ps,
                            type = "vmess",
                            server = vmess.add,
                            port = vmess.port,
                            uuid = vmess.id,
                            alterId = vmess.aid.toInt(),
                            cipher = vmess.type,
                            tls = ("" != vmess.tls),
                            network = if ("ws" == vmess.net) {
                                "ws"
                            } else {
                                null
                            },
                            wsPath = if ("ws" == vmess.net) {
                                vmess.path
                            } else {
                                null
                            },
                            wsHeaders = emptyMap(),
                            skipCertVerify = false
                        )
                    }
                }

                val proxyNames = clashVMesses.map {
                    it.name
                }

                loadTemplate().let {
                    val proxyGroups = mutableListOf<ProxyGroup>().apply {
                        for (proxyGroup in it.proxyGroup) {
                            if ((proxyGroup.name == "Proxy")
                                or (proxyGroup.name == "Foreign")
                                or (proxyGroup.name == "Domestic")
                                or (proxyGroup.name == "FallBack")
                                or (proxyGroup.name == "LoadBalance")
                            ) {
                                val proxies = proxyGroup.proxies.toMutableList()

                                proxies.addAll(proxyNames)

                                this.add(
                                    ProxyGroup(
                                        proxyGroup.name,
                                        proxyGroup.type,
                                        proxies,
                                        proxyGroup.url,
                                        proxyGroup.interval
                                    )
                                )

                                continue
                            }

                            this.add(proxyGroup)
                        }
                    }

                    ClashConfig(
                        it.port,
                        it.socksPort,
                        it.redirPort,
                        it.allowLan,
                        it.bindAddress,
                        it.mode,
                        it.logLevel,
                        it.externalController,
                        it.secret,
                        it.externalUI,
                        it.hosts,
                        it.dns,
                        clashVMesses,
                        proxyGroups,
                        it.rule
                    )
                }.run {
                    save()
                }
            }
        } else {
            println("Subscription url invalid!")
            exitProcess(1)
        }
    }
}

suspend fun readSubscription(subUrl: String): List<String> {
    val client = HttpClient()
    val content = client.get<String>(subUrl)
    return Base64.getDecoder().decode(content).toString(Charsets.UTF_8).split("\n")
}

suspend fun loadTemplate() = withContext(Dispatchers.IO) {
    javaClass.getResourceAsStream("/template.yaml").use {
        Yaml(configuration = YamlConfiguration(encodeDefaults = false)).parse(
            ClashConfig.serializer(),
            it.readBytes().toString(Charsets.UTF_8)
        )
    }
}

suspend fun ClashConfig.save() = withContext(Dispatchers.IO) {
    File("config.yaml").writeText(
        Yaml(configuration = YamlConfiguration(encodeDefaults = false)).stringify(
            ClashConfig.serializer(),
            this@save
        )
    )
}

fun main(args: Array<String>) = V2ray2Clash().main(args)
