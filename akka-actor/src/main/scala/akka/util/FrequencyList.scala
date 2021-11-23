/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.annotation.InternalApi

import scala.collection.{ immutable, mutable, AbstractIterator }
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object FrequencyList {
  def empty[A]: FrequencyList[A] = new FrequencyList[A](OptionVal.None)

  object withOverallRecency {
    def empty[A]: FrequencyList[A] = new FrequencyList[A](OptionVal.Some(new RecencyList.NanoClock))
  }

  private final class FrequencyNode[A](val count: Int) {
    var lessFrequent, moreFrequent: FrequencyNode[A] = this
    val empty = new Node[A](null.asInstanceOf[A], this)
    def leastRecent: Node[A] = empty.moreRecent
    def mostRecent: Node[A] = empty.lessRecent
  }

  private final class Node[A](val value: A, initialFrequency: FrequencyNode[A]) {
    var frequency: FrequencyNode[A] = initialFrequency
    var lessRecent, moreRecent: Node[A] = this
    var overallLessRecent, overallMoreRecent: Node[A] = this
    var timestamp: Long = 0L
  }
}

/**
 * INTERNAL API
 *
 * Mutable non-thread-safe frequency list.
 * Used for tracking frequency of elements for implementing least/most frequently used eviction policies.
 * Implemented using a doubly-linked list of doubly-linked lists, with lookup, so that all operations are constant time.
 * Elements with the same frequency are stored in order of update (recency within the same frequency count).
 * Overall recency can also be enabled, to support time-based eviction policies, without using a secondary recency list.
 */
@InternalApi
private[akka] final class FrequencyList[A](clock: OptionVal[RecencyList.Clock]) {
  import FrequencyList.{ FrequencyNode, Node }

  private val zero = new FrequencyNode[A](count = 0)
  private val lookupNode = mutable.Map.empty[A, Node[A]]

  def size: Int = lookupNode.size

  def update(value: A): FrequencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      increaseFrequency(node)
      addAsOverallMostRecent(node)
    } else {
      val node = addAsLeastFrequent(value)
      addAsOverallMostRecent(node)
      lookupNode += value -> node
    }
    this
  }

  def remove(value: A): FrequencyList[A] = {
    if (lookupNode.contains(value)) {
      val node = lookupNode(value)
      removeFromCurrentPosition(node)
      lookupNode -= value
    }
    this
  }

  def contains(value: A): Boolean = lookupNode.contains(value)

  private def leastFrequent: FrequencyNode[A] = zero.moreFrequent
  private def mostFrequent: FrequencyNode[A] = zero.lessFrequent

  private def leastNode: Node[A] = leastFrequent.leastRecent
  private def mostNode: Node[A] = mostFrequent.mostRecent

  private val lessFrequent: Node[A] => Node[A] = { node =>
    if (node.lessRecent ne node.frequency.empty) node.lessRecent
    else node.frequency.lessFrequent.mostRecent
  }

  private val moreFrequent: Node[A] => Node[A] = { node =>
    if (node.moreRecent ne node.frequency.empty) node.moreRecent
    else node.frequency.moreFrequent.leastRecent
  }

  def removeLeastFrequent(n: Int = 1, skip: OptionVal[A] = OptionVal.none[A]): immutable.Seq[A] =
    removeWhile(start = leastNode, next = moreFrequent, limit = n, skip = skip)

  def removeMostFrequent(n: Int = 1, skip: OptionVal[A] = OptionVal.none[A]): immutable.Seq[A] =
    removeWhile(start = mostNode, next = lessFrequent, limit = n, skip = skip)

  def leastToMostFrequent: Iterator[A] = iterator(start = leastNode, shift = moreFrequent)

  def mostToLeastFrequent: Iterator[A] = iterator(start = mostNode, shift = lessFrequent)

  private def overallLeastRecent: Node[A] = zero.empty.overallMoreRecent
  private def overallMostRecent: Node[A] = zero.empty.overallLessRecent

  private val overallLessRecent: Node[A] => Node[A] = _.overallLessRecent
  private val overallMoreRecent: Node[A] => Node[A] = _.overallMoreRecent

  def removeOverallLeastRecent(n: Int = 1): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    removeWhile(start = overallLeastRecent, next = overallMoreRecent, limit = n)
  }

  def removeOverallMostRecent(n: Int = 1): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    removeWhile(start = overallMostRecent, next = overallLessRecent, limit = n)
  }

  def removeOverallLeastRecentOutside(duration: FiniteDuration): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    val min = clock.get.earlierTime(duration)
    removeWhile(start = overallLeastRecent, next = overallMoreRecent, continueWhile = _.timestamp < min)
  }

  def removeOverallMostRecentWithin(duration: FiniteDuration): immutable.Seq[A] = {
    if (clock.isEmpty) throw new UnsupportedOperationException("Overall recency is not enabled for this FrequencyList")
    val max = clock.get.earlierTime(duration)
    removeWhile(start = overallMostRecent, next = overallLessRecent, continueWhile = _.timestamp > max)
  }

  def overallLeastToMostRecent: Iterator[A] = iterator(start = overallLeastRecent, shift = overallMoreRecent)

  def overallMostToLeastRecent: Iterator[A] = iterator(start = overallMostRecent, shift = overallLessRecent)

  private def addAsLeastFrequent(value: A): Node[A] = {
    val frequency = if (leastFrequent.count == 1) {
      leastFrequent
    } else {
      val frequencyOne = new FrequencyNode[A](count = 1)
      frequencyOne.lessFrequent = zero
      frequencyOne.moreFrequent = leastFrequent
      frequencyOne.lessFrequent.moreFrequent = frequencyOne
      frequencyOne.moreFrequent.lessFrequent = frequencyOne
      frequencyOne
    }
    val node = new Node(value, frequency)
    addToFrequency(node, frequency)
    node
  }

  private def increaseFrequency(node: Node[A]): Unit = {
    val nextFrequency = getOrInsertFrequencyAfter(node.frequency)
    removeFromCurrentPosition(node)
    addToFrequency(node, nextFrequency)
  }

  private def getOrInsertFrequencyAfter(frequency: FrequencyNode[A]): FrequencyNode[A] = {
    val nextCount = frequency.count + 1
    if (frequency.moreFrequent.count == nextCount) {
      frequency.moreFrequent
    } else {
      val nextFrequencyNode = new FrequencyNode[A](nextCount)
      nextFrequencyNode.lessFrequent = frequency
      nextFrequencyNode.moreFrequent = frequency.moreFrequent
      nextFrequencyNode.moreFrequent.lessFrequent = nextFrequencyNode
      frequency.moreFrequent = nextFrequencyNode
      nextFrequencyNode
    }
  }

  private def addToFrequency(node: Node[A], frequency: FrequencyNode[A]): Unit = {
    node.frequency = frequency
    node.moreRecent = frequency.empty
    node.lessRecent = frequency.mostRecent
    node.moreRecent.lessRecent = node
    node.lessRecent.moreRecent = node
  }

  private def addAsOverallMostRecent(node: Node[A]): Unit =
    if (clock.isDefined) {
      node.overallMoreRecent = zero.empty
      node.overallLessRecent = overallMostRecent
      node.overallMoreRecent.overallLessRecent = node
      node.overallLessRecent.overallMoreRecent = node
      node.timestamp = clock.get.currentTime()
    }

  private def removeFromCurrentPosition(node: Node[A]): Unit = {
    node.lessRecent.moreRecent = node.moreRecent
    node.moreRecent.lessRecent = node.lessRecent
    if (node.frequency.mostRecent eq node.frequency.empty) {
      node.frequency.lessFrequent.moreFrequent = node.frequency.moreFrequent
      node.frequency.moreFrequent.lessFrequent = node.frequency.lessFrequent
    }
    if (clock.isDefined) {
      node.overallLessRecent.overallMoreRecent = node.overallMoreRecent
      node.overallMoreRecent.overallLessRecent = node.overallLessRecent
    }
  }

  private val continueToLimit: Node[A] => Boolean = _ => true

  private def removeWhile(
      start: Node[A],
      next: Node[A] => Node[A],
      limit: Int = size,
      continueWhile: Node[A] => Boolean = continueToLimit,
      skip: OptionVal[A] = OptionVal.none[A]): immutable.Seq[A] = {
    var count = 0
    var node = start
    val values = mutable.ListBuffer.empty[A]
    while ((node ne zero.empty) && (count < limit) && continueWhile(node)) {
      if (!skip.contains(node.value)) {
        count += 1
        removeFromCurrentPosition(node)
        lookupNode -= node.value
        values += node.value
      }
      node = next(node)
    }
    values.result()
  }

  private def iterator(start: Node[A], shift: Node[A] => Node[A]): Iterator[A] =
    new AbstractIterator[A] {
      private[this] var current = start
      override def hasNext: Boolean = current ne zero.empty
      override def next(): A = {
        val value = current.value
        current = shift(current)
        value
      }
    }
}
