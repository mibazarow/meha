package com.andharness.ru.data.ssh

/**
 * Heuristic "this SSH command can destroy data or take a machine down" classifier (update.md §5.4).
 *
 * It is deliberately **pure** (no Android, no JSch) so its behaviour is pinned by unit tests, and it
 * errs on the side of flagging: a false positive only costs one extra confirmation, while a false
 * negative would let a destructive command run silently.
 *
 * How a command is read:
 * 1. quotes and backslashes are removed, so `sh -c 'rm -rf /'` tokenises like `sh -c rm -rf /`;
 * 2. the text is split into shell segments on `;`, `&&`, `||`, `|` and newlines;
 * 3. within a segment, leading wrapper words (`sudo`, `env`, `xargs`, `timeout`, `sh`, …), their
 *    options, option values (`sudo -u root …`) and durations (`timeout 5 …`) are skipped;
 * 4. the first remaining token is the *effective command word*, and the rest are its arguments.
 *
 * Every rule then looks at that command word and its arguments. Two rules — writing to a block device
 * and the fork bomb — additionally scan the whole text, because they are patterns rather than
 * commands.
 *
 * Honest limits (update.md §5.4): this does **not** follow variables, aliases, `eval`, `find -exec`,
 * base64 payloads, shell functions or arbitrary scripts. It recognises the forms listed in
 * update.md §5.4 (`rm -rf`, `rm -r`, `dd`, `mkfs*`, `reboot`, `shutdown`, `systemctl stop|disable`,
 * recursive `chmod`/`chown`, `> /dev/…`, `iptables -F`, the fork bomb) plus their obvious siblings.
 */
object DestructiveCommandPolicy {

    /** The classification of one command string. */
    data class Verdict(
        val destructive: Boolean,
        val reasons: List<String>
    ) {
        /** Human-readable Russian explanation of every matched rule (empty when safe). */
        val reasonText: String get() = reasons.joinToString("; ")
    }

    /** A matched rule: the Russian reason plus the predicate that triggers it. */
    private data class Rule(val reason: String, val matches: (CommandLine) -> Boolean)

    /** The effective command word of one shell segment, with its remaining arguments. */
    private data class CommandLine(val word: String, val args: List<String>) {
        /** True when any argument equals one of [flags] exactly. */
        fun hasFlag(vararg flags: String): Boolean = args.any { it in flags }

        /** True when any short-flag cluster contains [letter] (e.g. `-rf` contains `r`). */
        fun hasShortFlag(letter: Char): Boolean =
            args.any { arg ->
                arg.length > 1 && arg[0] == '-' && arg[1] != '-' && arg.contains(letter)
            }
    }

    fun inspect(command: String?): Verdict {
        val text = command.orEmpty()
        if (text.isBlank()) return Verdict(false, emptyList())
        val reasons = LinkedHashSet<String>()
        for (line in parseSegments(text)) {
            for (rule in COMMAND_RULES) {
                if (rule.matches(line)) reasons += rule.reason
            }
        }
        for (textRule in TEXT_RULES) {
            textRule(text)?.let { reasons += it }
        }
        return Verdict(reasons.isNotEmpty(), reasons.toList())
    }

    /** Convenience: `true` when the command must not run without an explicit confirmation. */
    fun isDestructive(command: String?): Boolean = inspect(command).destructive

    // ---- parsing ----

    private val SEGMENT_SEPARATORS = Regex("""(?:&&|\|\||;|\n|\|)""")

    private val QUOTES = Regex("""['"\\]""")

    /** Words that precede the real command; they are skipped along with their own arguments. */
    private val WRAPPERS = setOf(
        "sudo", "doas", "env", "nohup", "nice", "ionice", "time", "command", "exec", "setsid",
        "stdbuf", "busybox", "xargs", "timeout", "watch",
        "sh", "bash", "zsh", "dash", "ksh", "ash"
    )

    /** Wrapper options that CONSUME the next token (`sudo -u root rm …`), so it must be skipped too. */
    private val VALUE_TAKING_OPTIONS = setOf("-u", "-g", "-p", "-C", "-U")

    /** A bare duration argument such as `5`, `30s`, `1m` (what `timeout`/`watch` take first). */
    private val DURATION = Regex("""^\d+(\.\d+)?[smhd]?$""")

    private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""")

    private fun parseSegments(command: String): List<CommandLine> {
        val cleaned = command.replace(QUOTES, " ")
        return cleaned.split(SEGMENT_SEPARATORS).mapNotNull { segment ->
            val tokens = segment.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            var index = 0
            var word: String? = null
            while (index < tokens.size) {
                val token = tokens[index]
                when {
                    ASSIGNMENT.matches(token) -> index++
                    token.substringAfterLast('/') in WRAPPERS -> {
                        index++
                        index = skipWrapperArguments(tokens, index)
                    }
                    else -> {
                        word = token.substringAfterLast('/')
                        index++
                    }
                }
                if (word != null) break
            }
            word?.let { CommandLine(word = it, args = tokens.drop(index)) }
        }
    }

    /** Skips the options/durations that belong to a wrapper word, not to the wrapped command. */
    private fun skipWrapperArguments(tokens: List<String>, start: Int): Int {
        var index = start
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token in VALUE_TAKING_OPTIONS -> index += 2
                token.startsWith("-") -> index++
                DURATION.matches(token) -> index++
                else -> break
            }
        }
        return index
    }

    // ---- rules ----

    private val COMMAND_RULES: List<Rule> = listOf(
        Rule("рекурсивное удаление (rm -r/-rf)") { line ->
            line.word == "rm" &&
                (line.hasShortFlag('r') || line.hasShortFlag('R') || line.hasFlag("--recursive"))
        },
        Rule("низкоуровневая запись образа/данных (dd)") { it.word == "dd" },
        Rule("форматирование файловой системы (mkfs)") { it.word.startsWith("mkfs") },
        Rule("изменение разметки/swap (wipefs/fdisk/parted/mkswap)") {
            it.word in setOf("mkswap", "wipefs", "fdisk", "sfdisk", "sgdisk", "parted")
        },
        Rule("перезагрузка или выключение машины (reboot/shutdown/halt/poweroff)") {
            it.word in setOf("reboot", "shutdown", "halt", "poweroff")
        },
        Rule("остановка/отключение сервиса (systemctl stop|disable|mask|kill)") { line ->
            line.word == "systemctl" &&
                line.args.any { it in setOf("stop", "disable", "mask", "kill") }
        },
        Rule("рекурсивная смена прав/владельца (chmod -R / chown -R)") { line ->
            line.word in setOf("chmod", "chown", "chgrp") &&
                (line.hasShortFlag('R') || line.hasFlag("--recursive"))
        },
        Rule("сброс правил firewall (iptables -F)") { line ->
            line.word in setOf("iptables", "ip6tables", "nft") &&
                (line.hasFlag("-F", "--flush") || line.args.firstOrNull() == "flush")
        },
        Rule("уничтожение данных на диске (shred)") { it.word == "shred" },
        Rule("затирание суперблока RAID (mdadm --zero-superblock)") {
            it.word == "mdadm" && it.args.contains("--zero-superblock")
        }
    )

    private val DEVICE_REDIRECT = Regex("""(^|[^0-9])>\s*/dev/(sd|hd|vd|nvme|mmcblk|disk|loop)""")

    private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{.*:\s*\|\s*:.*\}""")

    private val TEXT_RULES: List<(String) -> String?> = listOf(
        { text ->
            if (DEVICE_REDIRECT.containsMatchIn(text)) {
                "запись напрямую на блочное устройство (> /dev/…)"
            } else {
                null
            }
        },
        { text -> if (FORK_BOMB.containsMatchIn(text)) "fork-бомба" else null }
    )
}
