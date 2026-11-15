package jp.hud.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Claude（Anthropic API）とのやり取り。ツール呼び出しのループもここで回す。 */
class ClaudeAgent(var apiKey: String, var model: String = "claude-haiku-5-5") {

    private var history = JSONArray()
    private var lastAt = 0L

    fun reset() { history = JSONArray() }

    /** 別スレッドから呼ぶこと */
    fun ask(userText: String, system: String, tools: JSONArray, exec: (String, JSONObject) -> String): String =
        askStream(userText, system, tools, exec) {}

    /**
     * 返事を少しずつ受け取り、1文できるたびに onSentence を呼ぶ（別スレッドから呼ぶこと）。
     * 最初の1文が届いた時点で読み上げを始められるので、返事が早く聞こえる。
     */
    fun askStream(userText: String, system: String, tools: JSONArray, exec: (String, JSONObject) -> String, onSentence: (String) -> Unit): String {
        val now = System.currentTimeMillis()
        if (now - lastAt > 15 * 60 * 1000 || history.length() > 30) reset()
        lastAt = now
        history.put(JSONObject().put("role", "user").put("content", userText))
        val spoken = StringBuilder()
        try {
            repeat(5) {
                val req = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 400)
                    .put("stream", true)
                    .put("system", system)
                    .put("tools", tools)
                    .put("messages", history)
                val (content, stop) = stream(req) { s -> spoken.append(s); onSentence(s) }
                history.put(JSONObject().put("role", "assistant").put("content", content))
                if (stop == "tool_use") {
                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val b = content.getJSONObject(i)
                        if (b.optString("type") == "tool_use") {
                            val out = try { exec(b.getString("name"), b.optJSONObject("input") ?: JSONObject()) }
                            catch (e: Exception) { "error: ${e.message}" }
                            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.getString("id")).put("content", out))
                        }
                    }
                    history.put(JSONObject().put("role", "user").put("content", results))
                } else {
                    return spoken.toString().trim().ifEmpty { "承知しました。".also(onSentence) }
                }
            }
            return "処理が長くなったので、いったん止めました。".also(onSentence)
        } catch (e: ApiError) {
            reset(); throw e
        } catch (e: Exception) {
            reset(); throw ApiError("通信できませんでした（${e.javaClass.simpleName}）")
        }
    }

    /** SSEで受け取り、ブロックを組み立てる。文の区切りごとに onSentence を呼ぶ */
    private fun stream(body: JSONObject, onSentence: (String) -> Unit): Pair<JSONArray, String> {
        val con = open(body)
        val code = con.responseCode
        if (code !in 200..299) fail(code, con.errorStream?.bufferedReader()?.readText() ?: "")
        val blocks = ArrayList<JSONObject>()
        val json = HashMap<Int, StringBuilder>()
        val texts = HashMap<Int, StringBuilder>()
        val pending = StringBuilder()
        var stop = "end_turn"
        fun flush(all: Boolean) {
            while (true) {
                val i = pending.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' || it == '\n' }
                if (i < 0) break
                val sentence = pending.substring(0, i + 1).trim()
                pending.delete(0, i + 1)
                if (sentence.isNotEmpty()) onSentence(sentence)
            }
            if (all) { val rest = pending.toString().trim(); pending.setLength(0); if (rest.isNotEmpty()) onSentence(rest) }
        }
        con.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val ev = try { JSONObject(line.substring(5).trim()) } catch (e: Exception) { continue }
                when (ev.optString("type")) {
                    "content_block_start" -> {
                        val idx = ev.getInt("index"); val cb = ev.getJSONObject("content_block")
                        while (blocks.size <= idx) blocks += JSONObject()
                        blocks[idx] = cb
                        if (cb.optString("type") == "text") texts[idx] = StringBuilder(cb.optString("text"))
                        if (cb.has("input")) json[idx] = StringBuilder()
                    }
                    "content_block_delta" -> {
                        val idx = ev.getInt("index"); val d = ev.getJSONObject("delta")
                        when (d.optString("type")) {
                            "text_delta" -> { val t = d.optString("text"); texts.getOrPut(idx) { StringBuilder() }.append(t); pending.append(t); flush(false) }
                            "input_json_delta" -> json.getOrPut(idx) { StringBuilder() }.append(d.optString("partial_json"))
                        }
                    }
                    "content_block_stop" -> flush(false)
                    "message_delta" -> ev.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { stop = it }
                    "error" -> throw ApiError(ev.optJSONObject("error")?.optString("message") ?: "エラーが起きました")
                }
            }
        }
        flush(true)
        val arr = JSONArray()
        blocks.forEachIndexed { i, b ->
            if (b.length() == 0) return@forEachIndexed
            when (b.optString("type")) {
                "text" -> { val t = texts[i]?.toString().orEmpty(); if (t.isNotEmpty()) arr.put(JSONObject().put("type", "text").put("text", t)) }
                else -> {
                    json[i]?.let { sb -> b.put("input", if (sb.isEmpty()) JSONObject() else try { JSONObject(sb.toString()) } catch (e: Exception) { JSONObject() }) }
                    arr.put(b)
                }
            }
        }
        return arr to stop
    }

    /** カメラ画像を見せて答えてもらう（会話履歴にも残すので、続けて質問できる） */
    fun vision(jpegB64: String, question: String, system: String): String {
        val now = System.currentTimeMillis()
        if (now - lastAt > 15 * 60 * 1000 || history.length() > 30) reset()
        lastAt = now
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegB64)))
            .put(JSONObject().put("type", "text").put("text", question))
        val msgs = JSONArray()
        for (i in 0 until history.length()) msgs.put(history.get(i))
        msgs.put(JSONObject().put("role", "user").put("content", content))
        try {
            val res = post(JSONObject().put("model", model).put("max_tokens", 500).put("system", system).put("messages", msgs))
            val arr = res.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.optString("type") == "text") sb.append(it.getString("text")) }
            val out = sb.toString().trim().ifEmpty { "よく見えませんでした。" }
            // 画像そのものは履歴に残さず、文字だけ残す（通信量の節約）
            history.put(JSONObject().put("role", "user").put("content", "［カメラ映像を見せた］$question"))
            history.put(JSONObject().put("role", "assistant").put("content", out))
            return out
        } catch (e: ApiError) { throw e }
        catch (e: Exception) { throw ApiError("通信できませんでした（${e.javaClass.simpleName}）") }
    }

    class ApiError(msg: String) : Exception(msg)

    private fun open(body: JSONObject): HttpURLConnection {
        val con = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 15000; con.readTimeout = 45000
        con.doOutput = true
        con.setRequestProperty("content-type", "application/json")
        con.setRequestProperty("x-api-key", apiKey)
        con.setRequestProperty("anthropic-version", "2023-06-01")
        con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return con
    }

    private fun fail(code: Int, text: String): Nothing {
        val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(120) }
        throw ApiError(when (code) {
            401 -> "APIキーが正しくないようです"
            429 -> "混み合っています。少し待ってからもう一度どうぞ"
            529 -> "Claudeが混雑しています。少し待ってからどうぞ"
            else -> "エラー $code：$msg"
        })
    }

    private fun post(body: JSONObject): JSONObject {
        val con = open(body)
        val code = con.responseCode
        val text = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) fail(code, text)
        return JSONObject(text)
    }

    companion object {
        fun tools(): JSONArray {
            fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
                .put("name", name).put("description", desc)
                .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
            fun str(d: String) = JSONObject().put("type", "string").put("description", d)
            fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
            return JSONArray()
                .put(tool("open_app", "端末のアプリを起動する。nameはアプリ名（一覧にある名前に近いもの）。",
                    JSONObject().put("name", str("アプリ名")), listOf("name")))
                .put(tool("media_control", "再生中の音楽・動画を操作する。",
                    JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("play", "pause", "next", "previous")))), listOf("action")))
                .put(tool("set_timer", "タイマーをセットする。",
                    JSONObject().put("seconds", int("秒数")).put("label", str("ラベル（任意）")), listOf("seconds")))
                .put(tool("set_alarm", "アラームをセットする（24時間表記）。",
                    JSONObject().put("hour", int("時")).put("minute", int("分")).put("label", str("ラベル（任意）")), listOf("hour", "minute")))
                .put(tool("flashlight", "ライト（フラッシュ）をオン・オフする。",
                    JSONObject().put("on", JSONObject().put("type", "boolean")), listOf("on")))
                .put(tool("web_search", "ブラウザでウェブ検索を開く。最新情報が必要なときに使う。",
                    JSONObject().put("query", str("検索語")), listOf("query")))
                .put(tool("open_notification", "最近の通知のうち指定番号のものを開く（番号は状況欄の通知一覧の番号）。",
                    JSONObject().put("index", int("通知番号")), listOf("index")))
                .put(tool("reply_notification", "返信可能な通知（状況欄で［返信可］のもの）に文章で返信する。必ず事前に送る内容を読み上げて相手の了承を得てから呼ぶこと。",
                    JSONObject().put("index", int("通知番号")).put("text", str("送る文章")), listOf("index", "text")))
                .put(tool("get_calendar", "カレンダーの予定を取得する。",
                    JSONObject().put("day", JSONObject().put("type", "string").put("enum", JSONArray(listOf("today", "tomorrow", "week")))), listOf("day")))
                .put(tool("camera_scan", "カメラのスキャンモードを開いて、目の前の物をカメラで見て答える。「これ何？」「この植物は？」など、見て判断する必要があるときに使う。",
                    JSONObject().put("question", str("カメラで見て答える質問")), listOf("question")))
                .put(tool("remember", "相手の好み・習慣・予定のパターン・頼まれた事柄など、今後に役立つ事実を短く覚えておく。パスワードやカード番号などの機密は覚えない。",
                    JSONObject().put("fact", str("覚える内容（短く）")), listOf("fact")))
                .put(tool("forget", "覚えている内容のうち、キーワードに一致するものを忘れる。",
                    JSONObject().put("keyword", str("忘れる内容のキーワード")), listOf("keyword")))
                .put(tool("open_pay", "決済アプリ（PayPayなど）を開く。action=pay はバーコード支払い画面、scan はスキャン画面、menu は支払いモジュールの画面、app はアプリのトップ。",
                    JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pay", "scan", "menu", "app")))), listOf("action")))
                .put(tool("add_task", "ホーム画面のTASKSパネルに、やることを1件追加する。「〜をタスクに入れて」「〜を忘れないように」と言われたときに使う。",
                    JSONObject().put("text", str("タスクの内容（短く）")), listOf("text")))
                .put(tool("complete_task", "TASKSパネルの未完了タスクのうち、キーワードに合うものを完了にする。",
                    JSONObject().put("keyword", str("完了にするタスクに含まれる言葉")), listOf("keyword")))
                .put(tool("end_conversation", "相手が会話を終えたがっているとき（「ありがとう、もういいよ」「大丈夫」「またね」「おやすみ」など）に使う。短いひとことの返事を言ったあと、会話を閉じて待機に戻る。",
                    JSONObject(), listOf()))
                .put(tool("call_contact", "連絡先の相手に電話をかける。nameは連絡先の名前。",
                    JSONObject().put("name", str("相手の名前")), listOf("name")))
        }
    }
}
