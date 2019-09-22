package com.github.k1rakishou.fsaf

import com.github.k1rakishou.fsaf.file.ExternalFile
import java.io.File

/**
 * Represents a snapshot of a directory with all of the inner directories and files. Useful, when
 * you have a directory with lots of files and you want to search for many files inside the directory.
 * We don't balance the tree because it is built from already existing balanced file tree.
 *
 * NOTE: Only use this when dealing with SAF files, i.e. ExternalFile. RawFile is backed by the
 * Java File so it doesn't need this at all. That's because ExternalFile is really slow when you
 * want to search for many files.
 * */
class FastFileSearchTree<T>(
  val root: FastFileSearchTreeNode<T> = FastFileSearchTreeNode(segmentName = FastFileSearchTreeNode.ROOT)
) {

  fun insertFile(file: ExternalFile, value: T): Boolean {
    val segments = file.getFileSegments().map { segment -> segment.name }
    require(segments.isNotEmpty()) { "Segments must not be empty" }

    return insertSegments(segments, value)
  }

  fun insertFiles(files: List<Pair<ExternalFile, T>>) {
    files.forEach { (file, value) -> insertFile(file, value) }
  }

  fun removeFile(file: ExternalFile): Boolean {
    val segments = file.getFileSegments().map { segment -> segment.name }
    require(segments.isNotEmpty()) { "Segments must not be empty" }

    return removeSegments(segments)
  }

  fun contains(file: ExternalFile): Boolean {
    val segments = file.getFileSegments().map { segment -> segment.name }
    require(segments.isNotEmpty()) { "Segments must not be empty" }

    return containsSegment(segments)
  }

  fun find(file: ExternalFile): T? {
    val segments = file.getFileSegments().map { segment -> segment.name }
    require(segments.isNotEmpty()) { "Segments must not be empty" }

    return findSegment(segments)
  }

  fun visitPath(segments: List<String>, func: (Int, FastFileSearchTreeNode<T>) -> Boolean) {
    require(segments.isNotEmpty()) { "Segments to visit list must not be empty" }
    root.visitPath(segments, 0, func)
  }

  fun visit(func: (FastFileSearchTreeNode<T>) -> Unit) {
    func(root)
    root.visit(func)
  }

  // Just to make it easier for the GC
  fun clear() {
    root.clear()
  }

  fun withTree(func: FastFileSearchTree<T>.() -> Unit) {
    func(this)
    clear()
  }

  fun insertSegments(segments: List<String>, value: T): Boolean {
    require(segments.isNotEmpty()) { "Segments must not be empty" }
    return root.insert(segments, value)
  }

  fun insertManySegments(manySegmentNames: List<Pair<List<String>, T>>): Boolean {
    return manySegmentNames.all { (segments, value) -> insertSegments(segments, value) }
  }

  /**
   * Removes a node with the innermost segment name, i.e:
   * Let's say we have the following segment:
   *    ROOT/123/456/111.txt
   *
   * If we call this method with these arguments:
   *    removeSegments(listOf("123"))
   * Then the sub-tree will be removed leaving only the ROOT node.
   *
   * But if we call this method with there arguments instead:
   *    removeSegments(listOf("123", "456", "111.txt"))
   * Then only the "111.txt" node will be removed leaving the following:
   *    ROOT/123/456
   * */
  fun removeSegments(segmentNames: List<String>): Boolean {
    require(segmentNames.isNotEmpty()) { "Segments must not be empty" }
    return root.remove(segmentNames)
  }

  fun containsSegment(segmentNames: List<String>): Boolean {
    require(segmentNames.isNotEmpty()) { "Segments must not be empty" }
    return root.contains(segmentNames)
  }

  fun findSegment(segmentNames: List<String>): T? {
    require(segmentNames.isNotEmpty()) { "Segments must not be empty" }
    return root.find(segmentNames)
  }

  override fun toString(): String {
    return root.toString()
  }
}

class FastFileSearchTreeNode<V>(
  // Current directory segment
  private var segmentName: String? = null,
  // Parent directory
  private var parent: FastFileSearchTreeNode<V>? = null,
  private var value: V? = null,
  // Files inside this directory
  private var children: MutableMap<String, FastFileSearchTreeNode<V>> =
    SINGLETON_MAP as MutableMap<String, FastFileSearchTreeNode<V>>
) {

  fun getNodeName(): String? = segmentName
  fun getNodeParent(): FastFileSearchTreeNode<V>? = parent
  fun getNodeChildren(): MutableMap<String, FastFileSearchTreeNode<V>> = children
  fun getNodeValue(): V? = value

  fun getFullPath(): String {
    if (parent == null) {
      return segmentName!!
    }

    return checkNotNull(parent).getFullPath() + File.separatorChar + segmentName!!
  }

  fun isRoot(): Boolean {
    return parent == null
  }

  fun isLeaf(): Boolean {
    return children.isEmpty()
  }

  fun isNode(): Boolean {
    return !isRoot() && !isLeaf()
  }

  internal fun insert(segments: List<String>, value: V): Boolean {
    if (segments.isEmpty()) {
      return false
    }

    val firstSegment = segments.first()

    if (children === SINGLETON_MAP) {
      children = mutableMapOf()
    }

    if (!children.containsKey(firstSegment)) {
      children[firstSegment] = FastFileSearchTreeNode(parent = this)
    }

    return children[firstSegment]!!.insertInternal(segments, value, 0)
  }

  internal fun remove(segments: List<String>): Boolean {
    if (segments.isEmpty()) {
      return false
    }

    if (children === SINGLETON_MAP) {
      return true
    }

    val firstSegment = segments.first()
    return children[firstSegment]?.removeInternal(segments, 0)
      ?: false
  }

  internal fun contains(segmentNames: List<String>): Boolean {
    return containsInternal(segmentNames, 0)
  }

  internal fun find(segmentNames: List<String>): V? {
    return findInternal(segmentNames, 0)
  }

  internal fun clear() {
    if (children === SINGLETON_MAP) {
      return
    }

    children.values.forEach { node ->
      node.clear()
      node.children.clear()
      node.children = singletonMap()
    }
  }

  internal fun visitPath(
    segments: List<String>,
    index: Int,
    func: (Int, FastFileSearchTreeNode<V>) -> Boolean
  ) {
    if (children === SINGLETON_MAP) {
      return
    }

    val currentSegmentName = segments.getOrNull(index)
      ?: return

    if (currentSegmentName != segmentName) {
      return
    }

    if (!func(index, this)) {
      return
    }

    children[currentSegmentName]?.visitPath(segments, index + 1, func)
  }

  internal fun visit(func: (FastFileSearchTreeNode<V>) -> Unit) {
    if (children === singletonMap()) {
      return
    }

    children.forEach { (_, node) ->
      func(node)
      node.visit(func)
    }
  }

  private fun insertInternal(
    segments: List<String>,
    value: V,
    index: Int
  ): Boolean {
    val currentSegment = segments.getOrNull(index)
      ?: return false
    val nextSegment = segments.getOrNull(index + 1)
    val isLastSegment = nextSegment == null
    this.segmentName = currentSegment

    if (isLastSegment || nextSegment == null) {
      this.value = value
      return true
    }

    if (children === SINGLETON_MAP) {
      children = mutableMapOf()
    }

    if (!children.containsKey(nextSegment)) {
      val newNode = FastFileSearchTreeNode(parent = this)
      children[nextSegment] = newNode
    }

    return children[nextSegment]!!.insertInternal(
      segments,
      value,
      index + 1
    )
  }

  private fun removeInternal(segments: List<String>, index: Int): Boolean {
    val segment = segments.getOrNull(index)
    val isLastSegment = segments.getOrNull(index + 1) == null

    if (segmentName != segment) {
      return false
    }

    if (segmentName == segment && isLastSegment) {
      if (parent!!.children !== singletonMap()) {
        parent!!.children.remove(segment)

        if (parent!!.children.isEmpty()) {
          parent!!.children = singletonMap()
        }
      }

      return true
    }

    val nextSegmentName = segments.getOrNull(index + 1)
      ?: return false

    return children[nextSegmentName]?.removeInternal(segments, index + 1)
      ?: false
  }

  private fun containsInternal(
    segments: List<String>,
    index: Int
  ): Boolean {
    val currentSegmentName = segments.getOrNull(index)
      ?: return isLeaf()

    if (isRoot()) {
      val nextNode = children[currentSegmentName]
        ?: return false

      if (nextNode === singletonMap()) {
        return false
      }

      return nextNode.containsInternal(segments, index)
    }

    if (currentSegmentName != segmentName) {
      return false
    }

    val nextSegmentName = segments.getOrNull(index + 1)
      ?: return true

    val nextNode = children[nextSegmentName]
      ?: return false

    if (nextNode === singletonMap()) {
      return false
    }

    return nextNode.containsInternal(segments, index + 1)
  }

  private fun findInternal(
    segments: List<String>,
    index: Int
  ): V? {
    val currentSegmentName = segments.getOrNull(index)
      ?: return null

    if (children === SINGLETON_MAP) {
      return null
    }

    if (isRoot()) {
      val nextNode = children[currentSegmentName]
        ?: return null

      return nextNode.findInternal(segments, index)
    }

    if (currentSegmentName != segmentName) {
      return null
    }

    val nextSegmentName = segments.getOrNull(index + 1)
      ?: return null

    val nextNode = children[nextSegmentName]
      ?: return null

    if (nextNode.isLeaf()) {
      return nextNode.value
    }

    return nextNode.findInternal(segments, index + 1)
  }

  private fun singletonMap() = SINGLETON_MAP as MutableMap<String, FastFileSearchTreeNode<V>>

  override fun equals(other: Any?): Boolean {
    if (other == null) {
      return false
    }

    if (other === this) {
      return true
    }

    if (other.javaClass != this.javaClass) {
      return false
    }

    other as FastFileSearchTreeNode<V>
    return this.getFullPath() == other.getFullPath()
  }

  override fun hashCode(): Int {
    return getFullPath().hashCode()
  }

  override fun toString(): String {
    return "segmentName = ${segmentName}, value = ${value}, children = ${children.size}"
  }

  class SingletonMap<K, V> : MutableMap<K, V> {

    override val size: Int
      get() = 0

    override fun containsKey(key: K): Boolean {
      return false
    }

    override fun containsValue(value: V): Boolean {
      return false
    }

    override fun get(key: K): V? {
      return null
    }

    override fun isEmpty(): Boolean {
      return true
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
      get() = throw IllegalAccessException("Cannot be used with SingletonMap")
    override val keys: MutableSet<K>
      get() = throw IllegalAccessException("Cannot be used with SingletonMap")
    override val values: MutableCollection<V>
      get() = throw IllegalAccessException("Cannot be used with SingletonMap")

    override fun clear() {
    }

    override fun put(key: K, value: V): V? {
      throw IllegalAccessException("Cannot be used with SingletonMap")
    }

    override fun putAll(from: Map<out K, V>) {
      throw IllegalAccessException("Cannot be used with SingletonMap")
    }

    override fun remove(key: K): V? {
      throw IllegalAccessException("Cannot be used with SingletonMap")
    }
  }

  companion object {
    const val ROOT = "<ROOT>"
    private val SINGLETON_MAP = SingletonMap<String, FastFileSearchTreeNode<Any>>()
  }
}