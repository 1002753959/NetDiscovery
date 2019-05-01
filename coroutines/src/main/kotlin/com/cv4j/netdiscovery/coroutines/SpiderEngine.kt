package com.cv4j.netdiscovery.coroutines

import com.alibaba.fastjson.JSON
import cn.netdiscovery.core.config.Constant
import cn.netdiscovery.core.domain.SpiderEntity
import cn.netdiscovery.core.domain.response.SpiderResponse
import cn.netdiscovery.core.domain.response.SpiderStatusResponse
import cn.netdiscovery.core.domain.response.SpidersResponse
import cn.netdiscovery.core.queue.Queue
import cn.netdiscovery.core.utils.UserAgent
import cn.netdiscovery.core.utils.VertxUtils
import com.cv4j.proxy.ProxyPool
import com.cv4j.proxy.domain.Proxy
import com.safframework.tony.common.collection.NoEmptyHashMap
import com.safframework.tony.common.utils.IOUtils
import com.safframework.tony.common.utils.Preconditions
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import io.vertx.core.http.HttpServer
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import lombok.Getter
import java.util.*

/**
 * Created by tony on 2018/8/8.
 */
class SpiderEngine private constructor(@field:Getter
                                       val queue: Queue? = null) {

    private val spiders = NoEmptyHashMap<String, Spider>()

    private lateinit var server: HttpServer

    init {

        initSpiderEngine()
    }

    /**
     * 初始化爬虫引擎，加载ua列表
     */
    private fun initSpiderEngine() {

        val uaList = Constant.uaList

        if (Preconditions.isNotBlank(uaList)) {

            Arrays.asList(*uaList)
                    .parallelStream()
                    .forEach {

                        str ->
                        this.javaClass.getResourceAsStream(str)?.use {

                            val inputString = IOUtils.inputStream2String(it)
                            if (Preconditions.isNotBlank(inputString)) {
                                val ss = inputString.split("\r\n".toRegex()).dropLastWhile { str.isEmpty() }.toTypedArray()
                                if (ss.isNotEmpty()) {

                                    Arrays.asList(*ss).forEach {
                                        UserAgent.uas.add(it)
                                    }
                                }
                            }
                        }
                    }
        }
    }

    fun proxyList(proxies: List<Proxy>): SpiderEngine {

        ProxyPool.addProxyList(proxies)
        return this
    }

    /**
     * 添加爬虫到SpiderEngine，由SpiderEngine来管理
     *
     * @param spider
     * @return
     */
    fun addSpider(spider: Spider?): SpiderEngine {

        if (spider != null) {

            if (!spiders.containsKey(spider.name)) {
                spiders[spider.name] = spider
            }
        }
        return this
    }

    /**
     * 在SpiderEngine中创建一个爬虫，使用SpiderEngine的Queue
     *
     * @param name
     * @return Spider
     */
    fun createSpider(name: String): Spider? {

        if (!spiders.containsKey(name)) {

            val spider = Spider.create(this.queue).name(name)
            spiders[name] = spider
            return spider
        }

        return null
    }

    /**
     * 对各个爬虫的状态进行监测，并返回json格式。
     * 如果要使用此方法，须放在run()/runWithRepeat()之前
     *
     * @param port
     */
    fun httpd(port: Int): SpiderEngine {

        server = VertxUtils.getVertx().createHttpServer()

        val router = Router.router(VertxUtils.getVertx())
        router.route().handler(BodyHandler.create())

        if (Preconditions.isNotBlank<Map<String, Spider>>(spiders)) {

            for ((_, spider) in spiders) {

                router.route("/netdiscovery/spider/" + spider.name).handler{ routingContext ->

                    // 所有的请求都会调用这个处理器处理
                    val response = routingContext.response()
                    response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON)

                    val entity = SpiderEntity()
                    entity.spiderName = spider.name
                    entity.spiderStatus = spider.spiderStatus
                    entity.leftRequestSize = spider.queue.getLeftRequests(spider.name)
                    entity.totalRequestSize = spider.queue.getTotalRequests(spider.name)
                    entity.consumedRequestSize = entity.totalRequestSize - entity.leftRequestSize
                    entity.queueType = spider.queue.javaClass.simpleName
                    entity.downloaderType = spider.downloader.javaClass.simpleName

                    val spiderResponse = SpiderResponse()
                    spiderResponse.code = Constant.OK_STATUS_CODE
                    spiderResponse.message = Constant.SUCCESS
                    spiderResponse.data = entity

                    // 写入响应并结束处理
                    response.end(JSON.toJSONString(spiderResponse))
                }

                router.post("/netdiscovery/spider/" + spider.name + "/status").handler{ routingContext ->

                    // 所有的请求都会调用这个处理器处理
                    val response = routingContext.response()
                    response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON)

                    val json = routingContext.getBodyAsJson()

                    var spiderStatusResponse: SpiderStatusResponse? = null

                    if (json != null) {

                        val status = json.getInteger("status")

                        spiderStatusResponse = SpiderStatusResponse()

                        when (status) {

                            Spider.SPIDER_STATUS_PAUSE -> {
                                spider.pause()
                                spiderStatusResponse.data = "SpiderEngine pause Spider ${spider.name} success"
                            }

                            Spider.SPIDER_STATUS_RESUME -> {
                                spider.resume()
                                spiderStatusResponse.data = "SpiderEngine resume Spider ${spider.name} success"
                            }

                            Spider.SPIDER_STATUS_STOPPED -> {
                                spider.forceStop()
                                spiderStatusResponse.data = "SpiderEngine stop Spider ${spider.name} success"
                            }

                            else -> {
                            }
                        }
                    }

                    spiderStatusResponse!!.code = Constant.OK_STATUS_CODE
                    spiderStatusResponse.message = Constant.SUCCESS

                    // 写入响应并结束处理
                    response.end(JSON.toJSONString(spiderStatusResponse))
                }
            }

            router.route("/netdiscovery/spiders/").handler{ routingContext ->

                // 所有的请求都会调用这个处理器处理
                val response = routingContext.response()
                response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON)

                val list = ArrayList<SpiderEntity>()

                var spider: Spider? = null
                var entity: SpiderEntity? = null

                for ((_, value) in spiders) {

                    spider = value

                    entity = SpiderEntity()
                    entity.spiderName = spider.name
                    entity.spiderStatus = spider.spiderStatus
                    entity.leftRequestSize = spider.queue.getLeftRequests(spider.name)
                    entity.totalRequestSize = spider.queue.getTotalRequests(spider.name)
                    entity.consumedRequestSize = entity.totalRequestSize - entity.leftRequestSize
                    entity.queueType = spider.queue.javaClass.simpleName
                    entity.downloaderType = spider.downloader.javaClass.simpleName
                    list.add(entity)
                }

                val spidersResponse = SpidersResponse()
                spidersResponse.code = Constant.OK_STATUS_CODE
                spidersResponse.message = Constant.SUCCESS
                spidersResponse.data = list

                // 写入响应并结束处理
                response.end(JSON.toJSONString(spidersResponse))
            }
        }

        server.requestHandler{ router.accept(it) }.listen(port)

        return this
    }

    /**
     * 关闭HttpServer
     */
    fun closeHttpServer() = server?.close()

//    /**
//     * 启动SpiderEngine中所有的spider，让每个爬虫并行运行起来
//     * 如果在SpiderEngine中某个Spider使用了repeateRequest()，则须使用runWithRepeat()
//     */
//    fun run() {
//
//        if (Preconditions.isNotBlank<Map<String, Spider>>(spiders)) {
//
//            spiders.entries
//                    .forEach{
//                        GlobalScope.launch {
//                            it.value.run()
//                        }
//                    }
//
//            Runtime.getRuntime().addShutdownHook(Thread {
//                println("stop all spiders")
//                stopSpiders()
//            })
//        }
//    }

    /**
     * 启动SpiderEngine中所有的spider，让每个爬虫并行运行起来。
     *
     */
    fun run() {

        if (Preconditions.isNotBlank<Map<String, Spider>>(spiders)) {

            runBlocking {

                Flowable.fromIterable(spiders.toMap().values)
                        .parallel(spiders.values.size)
                        .runOn(Schedulers.io())
                        .map { spider ->
                            spider.run()
                            spider
                        }
                        .sequential()
                        .subscribe()

                Runtime.getRuntime().addShutdownHook(Thread {
                    println("stop all spiders")
                    stopSpiders()
                })
            }

        }

    }

    /**
     * 基于爬虫的名字，从SpiderEngine中获取爬虫
     *
     * @param name
     */
    fun getSpider(name: String): Spider? = spiders[name]

    /**
     * 停止某个爬虫程序
     *
     * @param name
     */
    fun stopSpider(name: String) = spiders[name]?.stop()

    /**
     * 停止所有的爬虫程序
     */
    fun stopSpiders() {

        if (Preconditions.isNotBlank<Map<String, Spider>>(spiders)) {

            spiders.forEach { _, spider -> spider.stop() }
        }
    }

    companion object {

        @JvmStatic
        fun create(): SpiderEngine {

            return SpiderEngine()
        }

        @JvmStatic
        fun create(queue: Queue): SpiderEngine {

            return SpiderEngine(queue)
        }
    }
}