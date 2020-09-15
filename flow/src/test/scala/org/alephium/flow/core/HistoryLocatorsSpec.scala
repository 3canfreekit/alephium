package org.alephium.flow.core

import org.alephium.util.{AlephiumSpec, AVector}

class HistoryLocatorsSpec extends AlephiumSpec {
  it should "sample correct heights 0" in {
    HistoryLocators.sampleHeights(0, 0) is AVector(0)
    HistoryLocators.sampleHeights(0, 1) is AVector(0, 1)
    HistoryLocators.sampleHeights(0, 2) is AVector(0, 1, 2)
    HistoryLocators.sampleHeights(0, 3) is AVector(0, 1, 2, 3)
    HistoryLocators.sampleHeights(0, 4) is AVector(0, 1, 2, 3, 4)
    HistoryLocators.sampleHeights(0, 5) is AVector(0, 1, 2, 3, 4, 5)
    HistoryLocators.sampleHeights(0, 6) is AVector(0, 1, 2, 4, 5, 6)
    HistoryLocators.sampleHeights(0, 7) is AVector(0, 1, 2, 5, 6, 7)
    HistoryLocators.sampleHeights(0, 8) is AVector(0, 1, 2, 4, 6, 7, 8)
    HistoryLocators.sampleHeights(0, 9) is AVector(0, 1, 2, 4, 5, 7, 8, 9)
  }

  it should "sample correct heights 1" in {
    (4 to 10) foreach { k =>
      val left  = AVector(0) ++ AVector.tabulate(k)(i => 1 << i)
      val right = left.reverse.map((1 << k) - _).tail
      HistoryLocators.sampleHeights(0, 1 << k) is left ++ right
      forAll { from: Int =>
        val to = from + (1 << k)
        if (to >= from && from >= 0) {
          HistoryLocators.sampleHeights(from, to) is (left ++ right).map(from + _)
        }
      }
    }
  }
}
