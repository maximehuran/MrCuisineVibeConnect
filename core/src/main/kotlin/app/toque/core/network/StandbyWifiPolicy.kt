package app.toque.core.network

/** Only Toque's own standby cuts may be undone; a manually disabled radio stays disabled. */
class StandbyWifiPolicy(var cutInStandby: Boolean, var asleep: Boolean) {
    enum class Action { KEEP, ENABLE, DISABLE }
    private var transfers = 0

    @Synchronized fun hold(): AutoCloseable {
        transfers++
        return object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(this@StandbyWifiPolicy) {
                if (!closed) { closed = true; transfers-- }
            }
        }
    }

    @Synchronized fun action(radioOn: Boolean, owned: Boolean): Action = when {
        cutInStandby && asleep && transfers == 0 -> if (radioOn) Action.DISABLE else Action.KEEP
        owned && !radioOn -> Action.ENABLE
        else -> Action.KEEP
    }

    @Synchronized fun mayReleaseOwnership(): Boolean = !cutInStandby || !asleep
}
