package org.bible.reading.strategy

/**
 * Registry to register and look up custom strategies.
 */
object StrategyRegistry {
    private val customStrategies = mutableMapOf<String, Strategy>()

    fun registerStrategy(name: String, strategy: Strategy) {
        customStrategies[name] = strategy
    }

    fun unregisterStrategy(name: String) {
        customStrategies.remove(name)
    }

    fun getStrategy(name: String): Strategy? = customStrategies[name]

    fun getAllCustomStrategies(): Map<String, Strategy> = customStrategies.toMap()

    fun clear() {
        customStrategies.clear()
    }
}
