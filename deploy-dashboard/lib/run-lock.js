class RunLock {
  constructor(slotNames) {
    this._slots = new Set(slotNames);
    this._running = new Set();
  }

  _assertKnown(slot) {
    if (!this._slots.has(slot)) {
      throw new Error(`Unknown slot: ${slot}`);
    }
  }

  tryAcquire(slot) {
    this._assertKnown(slot);
    if (this._running.has(slot)) return false;
    this._running.add(slot);
    return true;
  }

  release(slot) {
    this._assertKnown(slot);
    this._running.delete(slot);
  }

  isRunning(slot) {
    this._assertKnown(slot);
    return this._running.has(slot);
  }

  statusAll() {
    const result = {};
    for (const slot of this._slots) {
      result[slot] = this._running.has(slot);
    }
    return result;
  }
}

module.exports = { RunLock };
