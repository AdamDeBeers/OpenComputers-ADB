package li.cil.oc.server.component

import java.util.concurrent.atomic.AtomicLong

import li.cil.oc.Settings

/**
 * Server-wide hash budget shared by all mining cards.
 *
 * The budget is refilled once per server tick (see EventHandler.onServerTickPre)
 * and handed out first-come, first-served. Values are tracked in milli-hashes so
 * that fractional per-tick allowances (e.g. 30000 H/s = 1500 hashes/tick) work.
 */
object MiningBudget {
  private final val Scale = 1000L

  private val tickCounter = new AtomicLong(0L)

  private val remaining = new AtomicLong(0L)

  /** Current server tick as seen by the mining cards. */
  def currentTick: Long = tickCounter.get()

  /** Called once per server tick before anything else runs. */
  def onServerTick(): Unit = {
    tickCounter.incrementAndGet()
    remaining.set(Settings.get.miningCardGlobalHashesPerSecond.toLong * Scale / 20L)
  }

  /** Tries to reserve up to `wanted` hashes. Returns the number actually reserved. */
  def reserve(wanted: Int): Int = {
    if (wanted <= 0) return 0
    while (true) {
      val current = remaining.get()
      val available = (current / Scale).toInt
      if (available <= 0) return 0
      val granted = math.min(wanted, available)
      if (remaining.compareAndSet(current, current - granted * Scale)) return granted
    }
    0
  }

  /** Returns hashes that were reserved but not used. */
  def release(count: Int): Unit = {
    if (count > 0) remaining.addAndGet(count * Scale)
  }
}
