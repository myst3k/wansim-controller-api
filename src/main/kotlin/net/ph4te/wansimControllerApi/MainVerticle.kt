package net.ph4te.wansimControllerApi

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.Json
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

@Suppress("unused")
class MainVerticle : AbstractVerticle() {

    companion object {
        const val LISTEN_PORT = 8080
    }

    override fun start(startFuture: Future<Void>) {

        val allowHeaders = hashSetOf<String>().apply {
            add("Access-Control-Allow-Origin")
            add("Content-Type")
            add("Accept")
            add("Authorization")
        }
        val allowMethods = hashSetOf<HttpMethod>().apply {
            add(HttpMethod.GET)
            add(HttpMethod.POST)
        }

        val router = Router.router(vertx).apply {
            route().handler(
                CorsHandler.create("*")
                    .allowedHeaders(allowHeaders)
                    .allowedMethods(allowMethods)
            )
            route().handler(BodyHandler.create())
        }.also {
            it.get("/").handler(this::handleDefault)
            it.get("/api/listInterfaces").handler(this::listInterfaces)
            it.get("/api/showInterface/:iface").handler(this::showInterface)
            it.get("/api/showInterfaces").handler(this::showInterfaces)
            it.post("/api/impairInterface/:iface/:loss/:delay").blockingHandler(this::impairInterface)
            it.post("/api/clearInterface/:iface").blockingHandler(this::clearInterface)
            it.post("/api/clearInterfaces").blockingHandler(this::clearInterfaces)
            it.post("/api/randomizeImpair").blockingHandler(this::randomizeImpair)
        }

        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(LISTEN_PORT) { http ->
                if (http.succeeded()) {
                    println("HTTP server started on port $LISTEN_PORT")
                }
            }
    }

    private fun handleDefault(routingContext: RoutingContext) {
        log.debug("${routingContext.request().method()} - ${routingContext.request().path()} - default path /")
        routingContext.request().response()
            .putHeader("content-type", "text/plain")
            .end("nothing to see here...")
    }

    private fun listInterfaces(routingContext: RoutingContext) {
        val interfaces = "/bin/listInterfaces".runCommand()?.trimEnd('\n')

        var intArr = arrayOf<String>()
        interfaces?.let {
            intArr = interfaces.split("\\s".toRegex()).map { i -> i.trim() }.toTypedArray()
        }

        log.info("Listing Interfaces: ${intArr.contentToString()}")
        routingContext.response()
            .putHeader("Content-Type", "application/json; charset=UTF-8")
            .end(Json.encodePrettily(intArr))
    }

    private fun showInterface(routingContext: RoutingContext) {
        val iface = routingContext.request().getParam("iface")

        var cmd = arrayListOf<String>()
        cmd.add("/bin/sh")
        cmd.add("-c")
        cmd.add("""tc qdisc show dev $iface""")
        val ifaceStats = cmd.runCommand()
        if (ifaceStats == null) {
            routingContext.fail(500)
        } else {
            val statArr = ifaceStats.split("\\s".toRegex())
            val lossIdx = statArr.indexOf("loss")
            val delayIdx = statArr.indexOf("delay")

            val response = Iface(name = iface)
            if (lossIdx != -1) {
                response.loss = statArr[lossIdx + 1].removeSuffix("%").toInt()
            }
            if (delayIdx != -1) {
                response.delay = statArr[delayIdx + 1].removeSuffix("ms").toFloat().toInt()
            }

            log.info("Show Interface: $iface loss: ${response.loss} delay: ${response.delay}")
            routingContext.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(Json.encodePrettily(response))
        }
    }

    private fun showInterfaces(routingContext: RoutingContext) {
        var cmd = arrayListOf<String>()
        cmd.add("/bin/sh")
        cmd.add("-c")
        cmd.add("""tc qdisc show""")
        val ifaceStats = cmd.runCommand()?.trimEnd('\n')
        if (ifaceStats == null) {
            log.debug("ifaceStats: null, returning 500 error")
            routingContext.fail(500)
        } else {
            val ifaceArr = ifaceStats.split("\n")
            val returnArr = arrayListOf<Iface>()
            ifaceArr.forEach { iface ->
                val i = iface.split("\\s".toRegex())
                val name = i[4]
                when {
                    name.contains("lo") -> return@forEach
                    name.contains("mgmt") -> return@forEach
                    name.contains("ifb") -> return@forEach
                    name.contains("internet") -> return@forEach
                    else -> {
                        val ifaceReturn = Iface(name = name)

                        val lossIdx = i.indexOf("loss")
                        val delayIdx = i.indexOf("delay")

                        if (lossIdx != -1) {
                            ifaceReturn.loss = i[lossIdx + 1].removeSuffix("%").toInt()
                        }
                        if (delayIdx != -1) {
                            ifaceReturn.delay = i[delayIdx + 1].removeSuffix("ms").toFloat().toInt()
                        }
                        returnArr.add(ifaceReturn)
                    }
                }
            }
            routingContext.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(Json.encodePrettily(returnArr))
        }
    }

    private fun impairInterface(routingContext: RoutingContext) {
        val iface = routingContext.request().getParam("iface")
        val loss = routingContext.request().getParam("loss").toInt()
        val delay = routingContext.request().getParam("delay").toInt()

        if (loss < 0 || loss > 100) {
            routingContext.fail(500)
        } else if (delay < 0 || delay > 3000) {
            routingContext.fail(500)
        } else if (loss == 0 && delay == 0) {
            routingContext.fail(500)
        } else {
            val impairment = if (loss > 0 && delay > 0) {
                "loss $loss% delay ${delay}ms"
            } else if (loss > 0) {
                "loss $loss%"
            } else {
                "delay ${delay}ms"
            }

            val qdisc = """
            |qdisc del dev $iface root
            |qdisc add dev $iface root netem $impairment
            """.trimMargin()

            var cmd = arrayListOf<String>()
            cmd.add("/bin/sh")
            cmd.add("-c")
            cmd.add("""echo "$qdisc" | sudo tc -force -b -""")
            cmd.runCommand()
            log.info("Impairing Interface: $iface to $loss% loss, with ${delay}ms delay")
            routingContext.response().end()
        }
    }

    private fun clearInterface(routingContext: RoutingContext) {
        val iface = routingContext.request().getParam("iface")
        var cmd = arrayListOf<String>()
        cmd.add("/bin/sh")
        cmd.add("-c")
        cmd.add("""sudo tc qdisc del dev $iface root""")
        cmd.runCommand()
        log.info("Clearing Interface: $iface of all impairments")
        routingContext.response().end()
    }

    private fun clearInterfaces(routingContext: RoutingContext) {
        val ifaceStr = "/bin/listInterfaces".runCommand()?.trimEnd('\n')

        var ifaces = arrayOf<String>()
        ifaceStr?.let {
            ifaces = ifaceStr.split("\\s".toRegex()).map { i -> i.trim() }.toTypedArray()
        }

        val qdisc = StringBuilder()
        ifaces.forEach {
            qdisc.append("qdisc del dev $it root\n")
        }

        var cmd = arrayListOf<String>()
        cmd.add("/bin/sh")
        cmd.add("-c")
        cmd.add("""echo "$qdisc" | sudo tc -force -b -""")
        cmd.runCommand()
        log.info("Clearing All Interfaces of impairments")
        routingContext.response().end()
    }

    private fun randomizeImpair(routingContext: RoutingContext) {
        val ifaceStr = "/bin/listInterfaces".runCommand()?.trimEnd('\n')

        var ifaces = arrayOf<String>()
        ifaceStr?.let {
            ifaces = ifaceStr.split("\\s".toRegex()).map { i -> i.trim() }.toTypedArray()
        }
        val ifacesLeft = ifaces.filter { it.contains("left") }
        val ifacesRight = ifaces.filter { it.contains("right") }
        val randLeft = ifacesLeft[(0..ifacesLeft.size-1).random()]
        val randRight = ifacesRight[(0..ifacesRight.size-1).random()]

        var qdisc = StringBuilder()
        ifaces.forEach {iface ->
            val loss = (1..50).random()
            val delay = (1..1000).random()

            if (iface == randLeft || iface == randRight) {
                qdisc.append("qdisc del dev $iface root\n")
            } else {
                qdisc.append("qdisc del dev $iface root\n")
                qdisc.append("qdisc add dev $iface root netem loss $loss% delay ${delay}ms\n")
            }
        }

        var cmd = arrayListOf<String>()
        cmd.add("/bin/sh")
        cmd.add("-c")
        cmd.add("""echo "$qdisc" | sudo tc -force -b -""")
        cmd.runCommand()
        log.info("Randomizing Interface Impairments")
        routingContext.response().end()
    }
}

fun String.runCommand(workingDir: File? = File(System.getProperty("user.home"))): String? {
    return try {
        val parts = this.split("\\s".toRegex())
        log.debug(parts.toString())
        val proc = ProcessBuilder(*parts.toTypedArray())
            .directory(workingDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        proc.waitFor(1, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText()
    } catch (e: IOException) {
        e.printStackTrace()
        null
    } finally {
    }
}

fun List<String>.runCommand(workingDir: File? = File(System.getProperty("user.home"))): String? {
    return try {
        log.debug(this.toString())
        val proc = ProcessBuilder(this)
            .directory(workingDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        proc.waitFor(3, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText()
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}


