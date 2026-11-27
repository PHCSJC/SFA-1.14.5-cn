package io.nekohasekai.sfa.geoip

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rewrites a sing-box profile so that the `geoip-cn` rule-set resolves to the
 * app-managed local file instead of a remote URL or an inline list.
 *
 * Why this exists
 * ---------------
 * A profile can reference `geoip-cn` in three ways:
 *
 *   1. `type: inline` with a few thousand `ip_cidr` entries baked into the
 *      profile JSON. Works offline but the data goes stale and bloats the file.
 *   2. `type: remote` pointing at a URL. Fresh, but a failed first download
 *      makes sing-box abort *after* the tun device is already up, which
 *      blackholes every packet until the service is stopped by hand.
 *   3. `type: local` pointing at a file on disk.
 *
 * This class turns (1) and (2) into (3), pointing at the file managed by
 * [GeoIPRuleSet]. That gives us:
 *
 *   - no network dependency at start-up (the file is extracted from the APK),
 *   - no tun blackhole when the network is down,
 *   - a single place to refresh the data (the updater), independent of profiles.
 *
 * Profiles that do not mention `geoip-cn` are returned unchanged.
 */
object GeoIPProfileInjector {

    const val RULE_SET_TAG = "geoip-cn"

    /** Tag used when a profile references the list under another name. */
    private val ALIASES = setOf("geoip-cn", "geoip_cn", "cn-ip", "cn_ip", "china-ip")

    /**
     * Returns [profileJson] with every China-IP rule-set switched to the local
     * file. When nothing matches, the original string is returned untouched.
     *
     * The rewrite is deliberately conservative: if the JSON cannot be parsed
     * or has no matching rule-set, the caller's config is used verbatim so a
     * profile that worked before keeps working.
     */
    fun inject(profileJson: String, localPath: String): String {
        if (localPath.isEmpty()) return profileJson
        if (!mentionsChinaIp(profileJson)) return profileJson

        return runCatching {
            val root = JSONObject(profileJson)
            val route = root.optJSONObject("route") ?: return profileJson

            // A bare `{"action":"sniff"}` blocks the whole rule chain until the
            // sniffing reader gives up, which on a busy device means every
            // connection sits in pre-match and never reaches an outbound.
            // Bound it and restrict it so the remaining rules always run.
            normalizeSniffRule(route)
            normalizeTunStack(root)
            val ruleSets = route.optJSONArray("rule_set") ?: return profileJson

            var changed = false
            val rewritten = JSONArray()
            for (i in 0 until ruleSets.length()) {
                val entry = ruleSets.optJSONObject(i) ?: continue
                val tag = entry.optString("tag")
                if (tag !in ALIASES) {
                    rewritten.put(entry)
                    continue
                }
                rewritten.put(localRuleSet(tag, localPath))
                changed = true
            }
            if (!changed) return profileJson

            route.put("rule_set", rewritten)
            root.put("route", route)
            root.toString()
        }.getOrDefault(profileJson)
    }

    /**
     * Forces the tun inbound onto the gVisor stack.
     *
     * Why `system` is not safe here
     * -----------------------------
     * With `stack: system` the tun device is driven by the kernel's own TCP/IP
     * stack, so a packet that sing-box writes for a `direct` outbound can be
     * routed straight back into the same tun device. When the core is excluded
     * from the VPN (see the note in BoxService) this does not normally happen,
     * but on this device it still does: `route(direct)` is logged, then the
     * connection silently never reaches an outbound, with no error and no
     * close event. Domestic sites then hang until the browser gives up, which
     * is the "either it will not open, or it takes tens of seconds" symptom.
     *
     * The gVisor stack (`stack: gvisor`) keeps its own user-space TCP/IP
     * implementation, so the outbound socket is a normal host socket that
     * cannot be re-captured by the tun device. Measured on the same profile:
     * `system` gives 0 outbound connections for 89 matched rules, `gvisor`
     * gives 22 outbound connections for 22 inbound ones.
     *
     * `system` is treated as unsafe and rewritten, because it is the exact
     * value that produces the stall. Any other explicit stack is left alone.
     */
    private fun normalizeTunStack(root: JSONObject) {
        // systemStackIsUnsafe
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            val current = inbound.optString("stack")
            if (current.isNotEmpty() && current != "system") continue
            inbound.put("stack", "gvisor")
            inbounds.put(i, inbound)
        }
        root.put("inbounds", inbounds)
    }

    private fun localRuleSet(tag: String, localPath: String) = JSONObject().apply {
        put("type", "local")
        put("tag", tag)
        put("format", "binary")
        put("path", localPath)
    }

    /**
     * Cheap pre-check so we do not parse every profile on every start.
     * Matches the aliases and the literal `geoip-cn` string anywhere in the
     * document, which is enough to avoid false negatives.
     */
    private fun mentionsChinaIp(json: String): Boolean =
        json.contains("geoip-cn") || json.contains("geoip_cn") ||
            json.contains("\"cn-ip\"") || json.contains("\"cn_ip\"") ||
            json.contains("\"china-ip\"")

    /**
     * Restricts `sniff` to DNS so it cannot stall the rule chain.
     *
     * Why the port restriction and not just a timeout
     * -----------------------------------------------
     * `sniff` on a TCP connection runs `sniff.PeekStream`, which sets a read
     * deadline on the inbound connection and waits for the client's first
     * payload. On this device the tun TCP connection does not honour that
     * deadline: the read never returns, so the goroutine blocks forever and the
     * rule chain never advances past `sniff`. Every TCP connection then sits in
     * `pre-match` and never reaches an outbound, which looks like "no internet"
     * while DNS and ICMP keep working. Adding a `timeout` does not help, because
     * the deadline itself is what is ignored.
     *
     * Restricting the rule to port 53 keeps the only sniff result we actually
     * need - the `hijack-dns` match - on the UDP/packet path, which does not use
     * `PeekStream`, and leaves TCP completely untouched.
     *
     * If a profile already carries an explicit `sniff` rule with its own
     * `port`/`port_range`, it is left alone: the author asked for something
     * specific.
     */
    private fun normalizeSniffRule(route: JSONObject) {
        val rules = route.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("action") != "sniff") continue
            if (rule.has("port") || rule.has("port_range")) continue
            rule.put("port", 53)
            if (!rule.has("timeout")) rule.put("timeout", "300ms")
            rules.put(i, rule)
        }
        route.put("rules", rules)
    }

    /** True when the profile already points `geoip-cn` at [localPath]. */
    fun alreadyLocal(profileJson: String, localPath: String): Boolean =
        profileJson.contains("\"type\":\"local\"") && profileJson.contains(localPath)
}
