/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.index.sbtree.multivalue;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.exception.OTooBigIndexKeyException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.OAlwaysGreaterKey;
import com.orientechnologies.orient.core.index.OAlwaysLessKey;
import com.orientechnologies.orient.core.index.OCompositeKey;
import com.orientechnologies.orient.core.iterator.OEmptyIterator;
import com.orientechnologies.orient.core.iterator.OEmptyMapEntryIterator;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * This is implementation which is based on B+-tree implementation threaded tree.
 * The main differences are:
 * <ol>
 * <li>Buckets are not compacted/removed if they are empty after deletion of item. They reused later when new items are added.</li>
 * <li>All non-leaf buckets have links to neighbor buckets which contain keys which are less/more than keys contained in current
 * bucket</li>
 * <ol/>
 * There is support of null values for keys, but values itself cannot be null. Null keys support is switched off by default if null
 * keys are supported value which is related to null key will be stored in separate file which has only one page.
 * Buckets/pages for usual (non-null) key-value entries can be considered as sorted array. The first bytes of page contains such
 * auxiliary information as size of entries contained in bucket, links to neighbors which contain entries with keys less/more than
 * keys in current bucket.
 * The next bytes contain sorted array of entries. Array itself is split on two parts. First part is growing from start to end, and
 * second part is growing from end to start.
 * First part is array of offsets to real key-value entries which are stored in second part of array which grows from end to start.
 * This array of offsets is sorted by accessing order according to key value. So we can use binary search to find requested key.
 * When new key-value pair is added we append binary presentation of this pair to the second part of array which grows from end of
 * page to start, remember value of offset for this pair, and find proper position of this offset inside of first part of array.
 * Such approach allows to minimize amount of memory involved in performing of operations and as result speed up data processing.
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 8/7/13
 */
public class OSBTreeMultiValue<K> extends ODurableComponent {
  private static final int               MAX_KEY_SIZE       = OGlobalConfiguration.SBTREE_MAX_KEY_SIZE.getValueAsInteger();
  private static final OAlwaysLessKey    ALWAYS_LESS_KEY    = new OAlwaysLessKey();
  private static final OAlwaysGreaterKey ALWAYS_GREATER_KEY = new OAlwaysGreaterKey();

  private static final int MAX_PATH_LENGTH = OGlobalConfiguration.SBTREE_MAX_DEPTH.getValueAsInteger();

  private final static long                  ROOT_INDEX       = 0;
  private final        Comparator<? super K> comparator       = ODefaultComparator.INSTANCE;
  private final        String                nullFileExtension;
  private              long                  fileId;
  private              long                  nullBucketFileId = -1;
  private              int                   keySize;
  private              OBinarySerializer<K>  keySerializer;
  private              OType[]               keyTypes;
  private              boolean               nullPointerSupport;
  private              OEncryption           encryption;

  OSBTreeMultiValue(String name, String dataFileExtension, String nullFileExtension, OAbstractPaginatedStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension);
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void create(OBinarySerializer<K> keySerializer, OType[] keyTypes, int keySize, boolean nullPointerSupport,
      OEncryption encryption) {
    assert keySerializer != null;
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(false);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Error during sbtree creation", this), e);
    }

    acquireExclusiveLock();
    try {

      this.keySize = keySize;
      if (keyTypes != null) {
        this.keyTypes = Arrays.copyOf(keyTypes, keyTypes.length);
      } else {
        this.keyTypes = null;
      }

      this.encryption = encryption;
      this.keySerializer = keySerializer;

      this.nullPointerSupport = nullPointerSupport;

      fileId = addFile(atomicOperation, getFullName());

      if (nullPointerSupport) {
        nullBucketFileId = addFile(atomicOperation, getName() + nullFileExtension);
      }

      OCacheEntry rootCacheEntry = addPage(atomicOperation, fileId);
      try {

        OSBTreeBucketMultiValue<K> rootBucket = new OSBTreeBucketMultiValue<>(rootCacheEntry, true, keySerializer, encryption);
        rootBucket.setTreeSize(0);

      } finally {
        releasePageFromWrite(atomicOperation, rootCacheEntry);
      }

      endAtomicOperation(false, null);
    } catch (IOException e) {
      try {
        endAtomicOperation(true, e);
      } catch (IOException e1) {
        OLogManager.instance().error(this, "Error during sbtree data rollback", e1);
      }
      throw OException.wrapException(new OSBTreeMultiValueException("Error creation of sbtree with name " + getName(), this), e);
    } catch (RuntimeException e) {
      try {
        endAtomicOperation(true, e);
      } catch (IOException e1) {
        OLogManager.instance().error(this, "Error during sbtree data rollback", e1);
      }
      throw e;
    } finally {
      releaseExclusiveLock();
    }
  }

  public boolean isNullPointerSupport() {
    acquireSharedLock();
    try {
      return nullPointerSupport;
    } finally {
      releaseSharedLock();
    }
  }

  public List<ORID> get(K key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        checkNullSupport(key);

        OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        if (key != null) {
          key = keySerializer.preprocess(key, (Object[]) keyTypes);

          BucketSearchResult bucketSearchResult = findBucket(key, atomicOperation);
          if (bucketSearchResult.itemIndex < 0) {
            return Collections.emptyList();
          }

          long pageIndex = bucketSearchResult.getLastPathItem();
          OCacheEntry keyBucketCacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
          try {
            OSBTreeBucketMultiValue<K> keyBucket = new OSBTreeBucketMultiValue<>(keyBucketCacheEntry, keySerializer, encryption);
            return keyBucket.getValues(bucketSearchResult.itemIndex);
          } finally {
            releasePageFromRead(atomicOperation, keyBucketCacheEntry);
          }
        } else {
          if (getFilledUpTo(atomicOperation, nullBucketFileId) == 0) {
            return Collections.emptyList();
          }

          final OCacheEntry nullBucketCacheEntry = loadPageForRead(atomicOperation, nullBucketFileId, 0, false);
          try {
            final OMultiValueNullBucket nullBucket = new OMultiValueNullBucket(nullBucketCacheEntry, false);
            return nullBucket.getValues();
          } finally {
            releasePageFromRead(atomicOperation, nullBucketCacheEntry);
          }
        }
      } finally {
        releaseSharedLock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during retrieving  of sbtree with name " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void put(K key, ORID value) {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(true);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Error during sbtree entrie put", this), e);
    }

    acquireExclusiveLock();
    try {
      checkNullSupport(key);

      if (key != null) {

        key = keySerializer.preprocess(key, (Object[]) keyTypes);
        final byte[] serializedKey = keySerializer.serializeNativeAsWhole(key, (Object[]) keyTypes);

        if (keySize > MAX_KEY_SIZE) {
          throw new OTooBigIndexKeyException(
              "Key size is more than allowed, operation was canceled. Current key size " + keySize + ", allowed  " + MAX_KEY_SIZE,
              getName());
        }

        BucketSearchResult bucketSearchResult = findBucket(key, atomicOperation);

        OCacheEntry keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, bucketSearchResult.getLastPathItem(), false);
        OSBTreeBucketMultiValue<K> keyBucket = new OSBTreeBucketMultiValue<>(keyBucketCacheEntry, keySerializer, encryption);

        final byte[] keyToInsert;
        keyToInsert = serializeKey(serializedKey);

        boolean isNew;
        int insertionIndex;
        if (bucketSearchResult.itemIndex >= 0) {
          insertionIndex = bucketSearchResult.itemIndex;
          isNew = false;
        } else {
          insertionIndex = -bucketSearchResult.itemIndex - 1;
          isNew = true;
        }

        while (!addEntry(keyBucket, insertionIndex, isNew, keyToInsert, value)) {
          bucketSearchResult = splitBucket(keyBucket, keyBucketCacheEntry, bucketSearchResult.path, insertionIndex, key,
              atomicOperation);

          insertionIndex = bucketSearchResult.itemIndex;

          long pageIndex = bucketSearchResult.getLastPathItem();

          if (pageIndex != keyBucketCacheEntry.getPageIndex()) {
            releasePageFromWrite(atomicOperation, keyBucketCacheEntry);

            keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false);
          }

          keyBucket = new OSBTreeBucketMultiValue<>(keyBucketCacheEntry, keySerializer, encryption);
        }

        releasePageFromWrite(atomicOperation, keyBucketCacheEntry);

        updateSize(1, atomicOperation);
      } else {
        OCacheEntry cacheEntry;
        boolean isNew = false;

        if (getFilledUpTo(atomicOperation, nullBucketFileId) == 0) {
          cacheEntry = addPage(atomicOperation, nullBucketFileId);
          isNew = true;
        } else {
          cacheEntry = loadPageForWrite(atomicOperation, nullBucketFileId, 0, false);
        }

        try {
          final OMultiValueNullBucket nullBucket = new OMultiValueNullBucket(cacheEntry, isNew);
          nullBucket.addValue(value);
        } finally {
          releasePageFromWrite(atomicOperation, cacheEntry);
        }

        updateSize(1, atomicOperation);
      }

      endAtomicOperation(false, null);
    } catch (IOException e) {
      rollback(e);
      throw OException.wrapException(new OSBTreeMultiValueException("Error during index update with key " + key, this), e);
    } catch (RuntimeException e) {
      rollback(e);
      throw e;
    } finally {
      releaseExclusiveLock();
    }
  }

  private static <K> boolean addEntry(OSBTreeBucketMultiValue<K> bucketMultiValue, int index, boolean isNew, byte[] key,
      ORID value) {
    if (isNew) {
      return bucketMultiValue.addNewLeafEntry(index, key, value);
    }

    return bucketMultiValue.appendNewLeafEntry(index, value);
  }

  public void close(boolean flush) {
    acquireExclusiveLock();
    try {
      readCache.closeFile(fileId, flush, writeCache);

      if (nullPointerSupport) {
        readCache.closeFile(nullBucketFileId, flush, writeCache);
      }

    } finally {
      releaseExclusiveLock();
    }
  }

  public void close() {
    close(true);
  }

  public void clear() {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(true);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Error during sbtree clear", this), e);
    }

    acquireExclusiveLock();
    try {
      truncateFile(atomicOperation, fileId);

      if (nullPointerSupport) {
        truncateFile(atomicOperation, nullBucketFileId);
      }

      OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, ROOT_INDEX, false);
      if (cacheEntry == null) {
        cacheEntry = addPage(atomicOperation, fileId);
      }

      try {
        OSBTreeBucketMultiValue<K> rootBucket = new OSBTreeBucketMultiValue<>(cacheEntry, true, keySerializer, encryption);

        rootBucket.setTreeSize(0);
      } finally {
        releasePageFromWrite(atomicOperation, cacheEntry);
      }

      endAtomicOperation(false, null);
    } catch (IOException e) {
      rollback(e);

      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during clear of sbtree with name " + getName(), this), e);
    } catch (RuntimeException e) {
      rollback(e);
      throw e;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void delete() {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(false);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Error during sbtree deletion", this), e);
    }

    acquireExclusiveLock();
    try {
      deleteFile(atomicOperation, fileId);

      if (nullPointerSupport) {
        deleteFile(atomicOperation, nullBucketFileId);
      }

      endAtomicOperation(false, null);
    } catch (Exception e) {
      rollback(e);
      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during delete of sbtree with name " + getName(), this), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void deleteWithoutLoad() {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(false);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Error during sbtree deletion", this), e);
    }

    acquireExclusiveLock();
    try {
      if (isFileExists(atomicOperation, getFullName())) {
        final long fileId = openFile(atomicOperation, getFullName());
        deleteFile(atomicOperation, fileId);
      }

      if (isFileExists(atomicOperation, getName() + nullFileExtension)) {
        final long nullFileId = openFile(atomicOperation, getName() + nullFileExtension);
        deleteFile(atomicOperation, nullFileId);
      }

      endAtomicOperation(false, null);
    } catch (IOException e) {
      rollback(e);
      throw OException.wrapException(new OSBTreeMultiValueException("Exception during deletion of sbtree " + getName(), this), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void load(String name, OBinarySerializer<K> keySerializer, OType[] keyTypes, int keySize, boolean nullPointerSupport,
      OEncryption encryption) {
    acquireExclusiveLock();
    try {
      this.keySize = keySize;
      if (keyTypes != null) {
        this.keyTypes = Arrays.copyOf(keyTypes, keyTypes.length);
      } else {
        this.keyTypes = null;
      }

      this.encryption = encryption;
      this.nullPointerSupport = nullPointerSupport;

      final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

      fileId = openFile(atomicOperation, getFullName());
      if (nullPointerSupport) {
        nullBucketFileId = openFile(atomicOperation, name + nullFileExtension);
      }

      this.keySerializer = keySerializer;
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Exception during loading of sbtree " + name, this), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

        OCacheEntry rootCacheEntry = loadPageForRead(atomicOperation, fileId, ROOT_INDEX, false);
        try {
          OSBTreeBucketMultiValue<K> rootBucket = new OSBTreeBucketMultiValue<>(rootCacheEntry, keySerializer, encryption);
          return rootBucket.getTreeSize();
        } finally {
          releasePageFromRead(atomicOperation, rootCacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during retrieving of size of index " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public boolean remove(K key, final ORID value) {
    final OAtomicOperation atomicOperation;
    try {
      atomicOperation = startAtomicOperation(true);
    } catch (IOException e) {
      throw OException.wrapException(new OSBTreeMultiValueException("Error during sbtree entrie remove", this), e);
    }

    boolean removed;
    acquireExclusiveLock();
    try {
      if (key != null) {
        key = keySerializer.preprocess(key, (Object[]) keyTypes);

        BucketSearchResult bucketSearchResult = findBucket(key, atomicOperation);
        if (bucketSearchResult.itemIndex < 0) {
          endAtomicOperation(false, null);
          return false;
        }

        removed = removeKey(atomicOperation, value, bucketSearchResult);
      } else {
        if (getFilledUpTo(atomicOperation, nullBucketFileId) == 0) {
          endAtomicOperation(false, null);
          return false;
        }

        removed = removeNullBucket(atomicOperation, value);
      }

      endAtomicOperation(false, null);
    } catch (IOException e) {
      rollback(e);

      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during removing key " + key + " from sbtree " + getName(), this), e);
    } catch (RuntimeException e) {
      rollback(e);
      throw e;
    } finally {
      releaseExclusiveLock();
    }

    return removed;
  }

  private boolean removeNullBucket(OAtomicOperation atomicOperation, ORID value) throws IOException {
    boolean removed;
    OCacheEntry nullCacheEntry = loadPageForWrite(atomicOperation, nullBucketFileId, 0, false);
    try {
      OMultiValueNullBucket nullBucket = new OMultiValueNullBucket(nullCacheEntry, false);
      removed = nullBucket.removeValue(value);
    } finally {
      releasePageFromWrite(atomicOperation, nullCacheEntry);
    }

    if (removed) {
      updateSize(-1, atomicOperation);
    }

    return removed;
  }

  private boolean removeKey(OAtomicOperation atomicOperation, ORID value, BucketSearchResult bucketSearchResult)
      throws IOException {
    final boolean removed;
    OCacheEntry keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, bucketSearchResult.getLastPathItem(), false);
    try {
      OSBTreeBucketMultiValue<K> keyBucket = new OSBTreeBucketMultiValue<>(keyBucketCacheEntry, keySerializer, encryption);

      removed = keyBucket.remove(bucketSearchResult.itemIndex, value);
      if (removed) {
        updateSize(-1, atomicOperation);
      }
    } finally {
      releasePageFromWrite(atomicOperation, keyBucketCacheEntry);
    }

    return removed;
  }

  public OSBTreeCursor<K, ORID> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (!ascSortOrder) {
          return iterateEntriesMinorDesc(key, inclusive);
        }

        return iterateEntriesMinorAsc(key, inclusive);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OSBTreeCursor<K, ORID> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return iterateEntriesMajorAsc(key, inclusive);
        }

        return iterateEntriesMajorDesc(key, inclusive);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public K firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

        final BucketSearchResult searchResult = firstItem(atomicOperation);
        if (searchResult == null) {
          return null;
        }

        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, searchResult.getLastPathItem(), false);
        try {
          OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);
          return bucket.getKey(searchResult.itemIndex);
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public K lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

        final BucketSearchResult searchResult = lastItem(atomicOperation);
        if (searchResult == null) {
          return null;
        }

        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, searchResult.getLastPathItem(), false);
        try {
          OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);
          return bucket.getKey(searchResult.itemIndex);
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during finding last key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OSBTreeKeyCursor<K> keyCursor() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        final BucketSearchResult searchResult = firstItem(atomicOperation);
        if (searchResult == null) {
          return prefetchSize -> null;
        }

        return new OSBTreeFullKeyCursor(searchResult.getLastPathItem());
      } finally {
        releaseSharedLock();
      }
    } catch (IOException e) {
      throw OException
          .wrapException(new OSBTreeMultiValueException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OSBTreeCursor<K, ORID> iterateEntriesBetween(K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive,
      boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive);
        } else {
          return iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void flush() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        writeCache.flush();
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this SB-tree.
   */
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  private void checkNullSupport(K key) {
    if (key == null && !nullPointerSupport) {
      throw new OSBTreeMultiValueException("Null keys are not supported.", this);
    }
  }

  private void rollback(Exception e) {
    try {
      endAtomicOperation(true, e);
    } catch (IOException e1) {
      OLogManager.instance().error(this, "Error during sbtree operation  rollback", e1);
    }
  }

  private void updateSize(long diffSize, OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry rootCacheEntry = loadPageForWrite(atomicOperation, fileId, ROOT_INDEX, false);
    try {
      OSBTreeBucketMultiValue<K> rootBucket = new OSBTreeBucketMultiValue<>(rootCacheEntry, keySerializer, encryption);
      rootBucket.setTreeSize(rootBucket.getTreeSize() + diffSize);
    } finally {
      releasePageFromWrite(atomicOperation, rootCacheEntry);
    }
  }

  private OSBTreeCursor<K, ORID> iterateEntriesMinorDesc(K key, boolean inclusive) {
    key = keySerializer.preprocess(key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorDesc(key, inclusive);

    return new OSBTreeCursorBackward(null, key, false, inclusive);
  }

  private OSBTreeCursor<K, ORID> iterateEntriesMinorAsc(K key, boolean inclusive) {
    key = keySerializer.preprocess(key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorAsc(key, inclusive);

    return new OSBTreeCursorForward(null, key, false, inclusive);
  }

  private K enhanceCompositeKeyMinorDesc(K key, boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private K enhanceCompositeKeyMinorAsc(K key, boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private OSBTreeCursor<K, ORID> iterateEntriesMajorAsc(K key, boolean inclusive) {
    key = keySerializer.preprocess(key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorAsc(key, inclusive);

    return new OSBTreeCursorForward(key, null, inclusive, false);
  }

  private OSBTreeCursor<K, ORID> iterateEntriesMajorDesc(K key, boolean inclusive) {
    acquireSharedLock();
    try {
      key = keySerializer.preprocess(key, (Object[]) keyTypes);
      key = enhanceCompositeKeyMajorDesc(key, inclusive);

      return new OSBTreeCursorBackward(key, null, inclusive, false);

    } finally {
      releaseSharedLock();
    }

  }

  private K enhanceCompositeKeyMajorAsc(K key, boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private K enhanceCompositeKeyMajorDesc(K key, boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private BucketSearchResult firstItem(OAtomicOperation atomicOperation) throws IOException {
    LinkedList<PagePathItemUnit> path = new LinkedList<>();

    long bucketIndex = ROOT_INDEX;

    OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);
    int itemIndex = 0;
    try {
      OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);

      while (true) {
        if (!bucket.isLeaf()) {
          if (bucket.isEmpty() || itemIndex > bucket.size()) {
            if (!path.isEmpty()) {
              PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return null;
            }
          } else {
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex < bucket.size()) {
              bucketIndex = bucket.getLeft(itemIndex);
            } else {
              bucketIndex = bucket.getRight(itemIndex - 1);
            }

            itemIndex = 0;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return null;
            }
          } else {
            final ArrayList<Long> resultPath = new ArrayList<>(path.size() + 1);
            for (PagePathItemUnit pathItemUnit : path) {
              resultPath.add(pathItemUnit.pageIndex);
            }

            resultPath.add(bucketIndex);
            return new BucketSearchResult(0, resultPath);
          }
        }

        releasePageFromRead(atomicOperation, cacheEntry);

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);

        bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);
      }
    } finally {
      releasePageFromRead(atomicOperation, cacheEntry);
    }
  }

  private BucketSearchResult lastItem(OAtomicOperation atomicOperation) throws IOException {
    LinkedList<PagePathItemUnit> path = new LinkedList<>();

    long bucketIndex = ROOT_INDEX;

    OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);

    OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);

    int itemIndex = bucket.size() - 1;
    try {
      while (true) {
        if (!bucket.isLeaf()) {
          if (itemIndex < -1) {
            if (!path.isEmpty()) {
              PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return null;
            }
          } else {
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex > -1) {
              bucketIndex = bucket.getRight(itemIndex);
            } else {
              bucketIndex = bucket.getLeft(0);
            }

            itemIndex = OSBTreeBucketMultiValue.MAX_PAGE_SIZE_BYTES + 1;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return null;
            }
          } else {
            final ArrayList<Long> resultPath = new ArrayList<>(path.size() + 1);
            for (PagePathItemUnit pathItemUnit : path) {
              resultPath.add(pathItemUnit.pageIndex);
            }

            resultPath.add(bucketIndex);

            return new BucketSearchResult(bucket.size() - 1, resultPath);
          }
        }

        releasePageFromRead(atomicOperation, cacheEntry);

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);

        bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);
        if (itemIndex == OSBTreeBucketMultiValue.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      releasePageFromRead(atomicOperation, cacheEntry);
    }
  }

  private OSBTreeCursor<K, ORID> iterateEntriesBetweenAscOrder(K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive) {
    keyFrom = keySerializer.preprocess(keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenAsc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenAsc(keyTo, toInclusive);

    return new OSBTreeCursorForward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private OSBTreeCursor<K, ORID> iterateEntriesBetweenDescOrder(K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive) {
    keyFrom = keySerializer.preprocess(keyFrom, (Object[]) keyTypes);
    keyTo = keySerializer.preprocess(keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenDesc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenDesc(keyTo, toInclusive);

    return new OSBTreeCursorBackward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private K enhanceToCompositeKeyBetweenAsc(K keyTo, boolean toInclusive) {
    PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo);
    return keyTo;
  }

  private K enhanceFromCompositeKeyBetweenAsc(K keyFrom, boolean fromInclusive) {
    PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom);
    return keyFrom;
  }

  private K enhanceToCompositeKeyBetweenDesc(K keyTo, boolean toInclusive) {
    PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo);
    return keyTo;
  }

  private K enhanceFromCompositeKeyBetweenDesc(K keyFrom, boolean fromInclusive) {
    PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom);
    return keyFrom;
  }

  private BucketSearchResult splitBucket(OSBTreeBucketMultiValue<K> bucketToSplit, OCacheEntry entryToSplit, List<Long> path,
      int keyIndex, K keyToInsert, OAtomicOperation atomicOperation) throws IOException {
    final boolean splitLeaf = bucketToSplit.isLeaf();
    final int bucketSize = bucketToSplit.size();

    int indexToSplit = bucketSize >>> 1;
    final byte[] serializedSeparationKey = bucketToSplit.getRawKey(indexToSplit);
    final K separationKey = deserializeKey(serializedSeparationKey);

    final List<OSBTreeBucketMultiValue.Entry> rightEntries = new ArrayList<>(indexToSplit);

    final int startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

    if (splitLeaf) {
      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getLeafEntry(i));
      }
    } else {
      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getNonLeafEntry(i));
      }
    }

    if (entryToSplit.getPageIndex() != ROOT_INDEX) {
      return splitNonRootBucket(path, keyIndex, keyToInsert, entryToSplit.getPageIndex(), bucketToSplit, splitLeaf, indexToSplit,
          separationKey, serializedSeparationKey, rightEntries, atomicOperation);
    } else {
      return splitRootBucket(path, keyIndex, keyToInsert, entryToSplit, bucketToSplit, splitLeaf, indexToSplit, separationKey,
          serializedSeparationKey, rightEntries, atomicOperation);
    }
  }

  private byte[] serializeKey(byte[] serializedKey) {
    byte[] keyToInsert;
    if (encryption == null) {
      keyToInsert = serializedKey;
    } else {
      final byte[] encryptedKey = encryption.encrypt(serializedKey);

      keyToInsert = new byte[OIntegerSerializer.INT_SIZE + encryptedKey.length];
      OIntegerSerializer.INSTANCE.serializeNative(encryptedKey.length, keyToInsert, 0);
      System.arraycopy(encryptedKey, 0, keyToInsert, OIntegerSerializer.INT_SIZE, encryptedKey.length);
    }
    return keyToInsert;
  }

  private K deserializeKey(final byte[] serializedKey) {
    if (encryption == null) {
      return keySerializer.deserializeNativeObject(serializedKey, 0);
    }

    byte[] decrypted = encryption
        .decrypt(serializedKey, OIntegerSerializer.INT_SIZE, serializedKey.length - OIntegerSerializer.INT_SIZE);
    return keySerializer.deserializeNativeObject(decrypted, 0);
  }

  private BucketSearchResult splitNonRootBucket(List<Long> path, int keyIndex, K keyToInsert, long pageIndex,
      OSBTreeBucketMultiValue<K> bucketToSplit, boolean splitLeaf, int indexToSplit, K separationKey,
      byte[] serializedSeparationKey, List<OSBTreeBucketMultiValue.Entry> rightEntries, OAtomicOperation atomicOperation)
      throws IOException {
    OCacheEntry rightBucketEntry = addPage(atomicOperation, fileId);

    try {
      OSBTreeBucketMultiValue<K> newRightBucket = new OSBTreeBucketMultiValue<>(rightBucketEntry, splitLeaf, keySerializer,
          encryption);
      newRightBucket.addAll(rightEntries);

      bucketToSplit.shrink(indexToSplit);

      if (splitLeaf) {
        long rightSiblingPageIndex = bucketToSplit.getRightSibling();

        newRightBucket.setRightSibling(rightSiblingPageIndex);
        newRightBucket.setLeftSibling(pageIndex);

        bucketToSplit.setRightSibling(rightBucketEntry.getPageIndex());

        if (rightSiblingPageIndex >= 0) {
          final OCacheEntry rightSiblingBucketEntry = loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, false);
          OSBTreeBucketMultiValue<K> rightSiblingBucket = new OSBTreeBucketMultiValue<>(rightSiblingBucketEntry, keySerializer,
              encryption);
          try {
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          } finally {
            releasePageFromWrite(atomicOperation, rightSiblingBucketEntry);
          }
        }
      }

      long parentIndex = path.get(path.size() - 2);
      OCacheEntry parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, false);
      try {
        OSBTreeBucketMultiValue<K> parentBucket = new OSBTreeBucketMultiValue<>(parentCacheEntry, keySerializer, encryption);
        int insertionIndex = parentBucket.find(separationKey);
        assert insertionIndex < 0;

        insertionIndex = -insertionIndex - 1;

        while (!parentBucket
            .addNonLeafEntry(insertionIndex, serializedSeparationKey, (int) pageIndex, (int) rightBucketEntry.getPageIndex(),
                true)) {
          BucketSearchResult bucketSearchResult = splitBucket(parentBucket, parentCacheEntry, path.subList(0, path.size() - 1),
              insertionIndex, separationKey, atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          insertionIndex = bucketSearchResult.itemIndex;

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            releasePageFromWrite(atomicOperation, parentCacheEntry);
            parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, false);
          }

          parentBucket = new OSBTreeBucketMultiValue<>(parentCacheEntry, keySerializer, encryption);
        }

      } finally {
        releasePageFromWrite(atomicOperation, parentCacheEntry);
      }

    } finally {
      releasePageFromWrite(atomicOperation, rightBucketEntry);
    }

    ArrayList<Long> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

    if (comparator.compare(keyToInsert, separationKey) < 0) {
      resultPath.add(pageIndex);
      return new BucketSearchResult(keyIndex, resultPath);
    }

    resultPath.add(rightBucketEntry.getPageIndex());
    if (splitLeaf) {
      return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
    }

    resultPath.add(rightBucketEntry.getPageIndex());
    return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);
  }

  private BucketSearchResult splitRootBucket(List<Long> path, int keyIndex, K keyToInsert, OCacheEntry bucketEntry,
      OSBTreeBucketMultiValue<K> bucketToSplit, boolean splitLeaf, int indexToSplit, K separationKey,
      byte[] serializedSeparationKey, List<OSBTreeBucketMultiValue.Entry> rightEntries, OAtomicOperation atomicOperation)
      throws IOException {
    final long treeSize = bucketToSplit.getTreeSize();
    final List<OSBTreeBucketMultiValue.Entry> leftEntries = new ArrayList<>(indexToSplit);

    if (splitLeaf) {
      for (int i = 0; i < indexToSplit; i++) {
        leftEntries.add(bucketToSplit.getLeafEntry(i));
      }
    } else {
      for (int i = 0; i < indexToSplit; i++) {
        leftEntries.add(bucketToSplit.getNonLeafEntry(i));
      }
    }

    OCacheEntry leftBucketEntry = addPage(atomicOperation, fileId);
    OCacheEntry rightBucketEntry = addPage(atomicOperation, fileId);
    try {
      OSBTreeBucketMultiValue<K> newLeftBucket = new OSBTreeBucketMultiValue<>(leftBucketEntry, splitLeaf, keySerializer,
          encryption);
      newLeftBucket.addAll(leftEntries);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      releasePageFromWrite(atomicOperation, leftBucketEntry);
    }

    try {
      OSBTreeBucketMultiValue<K> newRightBucket = new OSBTreeBucketMultiValue<>(rightBucketEntry, splitLeaf, keySerializer,
          encryption);
      newRightBucket.addAll(rightEntries);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      releasePageFromWrite(atomicOperation, rightBucketEntry);
    }

    bucketToSplit = new OSBTreeBucketMultiValue<>(bucketEntry, false, keySerializer, encryption);

    bucketToSplit.setTreeSize(treeSize);

    bucketToSplit
        .addNonLeafEntry(0, serializedSeparationKey, (int) leftBucketEntry.getPageIndex(), (int) rightBucketEntry.getPageIndex(),
            true);

    ArrayList<Long> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

    if (comparator.compare(keyToInsert, separationKey) < 0) {
      resultPath.add(leftBucketEntry.getPageIndex());
      return new BucketSearchResult(keyIndex, resultPath);
    }

    resultPath.add(rightBucketEntry.getPageIndex());

    if (splitLeaf) {
      return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
    }

    return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);
  }

  private BucketSearchResult findBucket(K key, OAtomicOperation atomicOperation) throws IOException {
    long pageIndex = ROOT_INDEX;
    final ArrayList<Long> path = new ArrayList<>();

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new OSBTreeMultiValueException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state. You should rebuild index related to given query.",
            this);
      }

      path.add(pageIndex);
      final OCacheEntry bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
      try {
        final OSBTreeBucketMultiValue<K> keyBucket = new OSBTreeBucketMultiValue<>(bucketEntry, keySerializer, encryption);
        final int index = keyBucket.find(key);

        if (keyBucket.isLeaf()) {
          return new BucketSearchResult(index, path);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
        } else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }
        }
      } finally {
        releasePageFromRead(atomicOperation, bucketEntry);
      }
    }
  }

  private K enhanceCompositeKey(K key, PartialSearchMode partialSearchMode) {
    if (!(key instanceof OCompositeKey)) {
      return key;
    }

    final OCompositeKey compositeKey = (OCompositeKey) key;

    if (!(keySize == 1 || compositeKey.getKeys().size() == keySize || partialSearchMode.equals(PartialSearchMode.NONE))) {
      final OCompositeKey fullKey = new OCompositeKey(compositeKey);
      int itemsToAdd = keySize - fullKey.getKeys().size();

      final Comparable<?> keyItem;
      if (partialSearchMode.equals(PartialSearchMode.HIGHEST_BOUNDARY)) {
        keyItem = ALWAYS_GREATER_KEY;
      } else {
        keyItem = ALWAYS_LESS_KEY;
      }

      for (int i = 0; i < itemsToAdd; i++) {
        fullKey.addKey(keyItem);
      }

      //noinspection unchecked
      return (K) fullKey;
    }

    return key;
  }

  /**
   * Indicates search behavior in case of {@link OCompositeKey} keys that have less amount of internal keys are used, whether
   * lowest or highest partially matched key should be used.
   */
  private enum PartialSearchMode {
    /**
     * Any partially matched key will be used as search result.
     */
    NONE,
    /**
     * The biggest partially matched key will be used as search result.
     */
    HIGHEST_BOUNDARY,

    /**
     * The smallest partially matched key will be used as search result.
     */
    LOWEST_BOUNDARY
  }

  public interface OSBTreeCursor<K, V> {
    Map.Entry<K, V> next(int prefetchSize);
  }

  public interface OSBTreeKeyCursor<K> {
    K next(int prefetchSize);
  }

  private static class BucketSearchResult {
    private final int             itemIndex;
    private final ArrayList<Long> path;

    private BucketSearchResult(int itemIndex, ArrayList<Long> path) {
      this.itemIndex = itemIndex;
      this.path = path;
    }

    long getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {
    private final long pageIndex;
    private final int  itemIndex;

    private PagePathItemUnit(long pageIndex, int itemIndex) {
      this.pageIndex = pageIndex;
      this.itemIndex = itemIndex;
    }
  }

  public class OSBTreeFullKeyCursor implements OSBTreeKeyCursor<K> {
    private long pageIndex;
    private int  itemIndex;

    private List<K>     keysCache    = new ArrayList<>();
    private Iterator<K> keysIterator = new OEmptyIterator<>();

    OSBTreeFullKeyCursor(long startPageIndex) {
      pageIndex = startPageIndex;
      itemIndex = 0;
    }

    @Override
    public K next(int prefetchSize) {
      if (keysIterator == null) {
        return null;
      }

      if (keysIterator.hasNext()) {
        return keysIterator.next();
      }

      keysCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      if (prefetchSize == 0) {
        prefetchSize = 1;
      }

      atomicOperationsManager.acquireReadLock(OSBTreeMultiValue.this);
      try {
        acquireSharedLock();
        try {
          OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

          while (keysCache.size() < prefetchSize) {
            if (pageIndex == -1) {
              break;
            }

            if (pageIndex >= getFilledUpTo(atomicOperation, fileId)) {
              pageIndex = -1;
              break;
            }

            final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
            try {
              final OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);

              if (itemIndex >= bucket.size()) {
                pageIndex = bucket.getRightSibling();
                itemIndex = 0;
                continue;
              }

              keysCache.add(deserializeKey(bucket.getRawKey(itemIndex)));
              itemIndex++;
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (IOException e) {
        throw OException.wrapException(new OSBTreeMultiValueException("Error during element iteration", OSBTreeMultiValue.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(OSBTreeMultiValue.this);
      }

      if (keysCache.isEmpty()) {
        keysCache = null;
        return null;
      }

      keysIterator = keysCache.iterator();
      return keysIterator.next();
    }
  }

  private final class OSBTreeCursorForward implements OSBTreeCursor<K, ORID> {
    private       K       fromKey;
    private final K       toKey;
    private       boolean fromKeyInclusive;
    private final boolean toKeyInclusive;

    private final List<Map.Entry<K, ORID>>     dataCache         = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private       Iterator<Map.Entry<K, ORID>> dataCacheIterator = OEmptyMapEntryIterator.INSTANCE;

    private OSBTreeCursorForward(K fromKey, K toKey, boolean fromKeyInclusive, boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (fromKey == null) {
        this.fromKeyInclusive = true;
      }
    }

    public Map.Entry<K, ORID> next(int prefetchSize) {
      if (dataCacheIterator == null) {
        return null;
      }

      if (dataCacheIterator.hasNext()) {
        final Map.Entry<K, ORID> entry = dataCacheIterator.next();

        fromKey = entry.getKey();
        fromKeyInclusive = false;

        return entry;
      }

      dataCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      if (prefetchSize == 0) {
        prefetchSize = 1;
      }

      atomicOperationsManager.acquireReadLock(OSBTreeMultiValue.this);
      try {
        acquireSharedLock();
        try {
          OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (fromKey != null) {
            bucketSearchResult = findBucket(fromKey, atomicOperation);
          } else {
            bucketSearchResult = firstItem(atomicOperation);
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return null;
          }

          long pageIndex = bucketSearchResult.getLastPathItem();
          int itemIndex;

          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = fromKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 1;
          }

          while (dataCache.size() < prefetchSize) {
            if (pageIndex == -1) {
              break;
            }

            final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
            try {
              final OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);

              if (itemIndex >= bucket.size()) {
                pageIndex = bucket.getRightSibling();
                itemIndex = 0;
                continue;
              }

              final OSBTreeBucketMultiValue.LeafEntry leafEntry = bucket.getLeafEntry(itemIndex);
              itemIndex++;

              final K key = deserializeKey(leafEntry.key);

              if (fromKey != null && (fromKeyInclusive ?
                  comparator.compare(key, fromKey) < 0 :
                  comparator.compare(key, fromKey) <= 0)) {
                continue;
              }

              if (toKey != null && (toKeyInclusive ? comparator.compare(key, toKey) > 0 : comparator.compare(key, toKey) >= 0)) {
                break;
              }

              for (final ORID rid : leafEntry.values) {
                dataCache.add(new Map.Entry<K, ORID>() {
                  @Override
                  public K getKey() {
                    return key;
                  }

                  @Override
                  public ORID getValue() {
                    return rid;
                  }

                  @Override
                  public ORID setValue(ORID value) {
                    throw new UnsupportedOperationException("setValue");
                  }
                });
              }
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (IOException e) {
        throw OException.wrapException(new OSBTreeMultiValueException("Error during element iteration", OSBTreeMultiValue.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(OSBTreeMultiValue.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return null;
      }

      dataCacheIterator = dataCache.iterator();

      final Map.Entry<K, ORID> entry = dataCacheIterator.next();

      fromKey = entry.getKey();
      fromKeyInclusive = false;

      return entry;
    }
  }

  private final class OSBTreeCursorBackward implements OSBTreeCursor<K, ORID> {
    private final K       fromKey;
    private       K       toKey;
    private final boolean fromKeyInclusive;
    private       boolean toKeyInclusive;

    private final List<Map.Entry<K, ORID>>     dataCache         = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private       Iterator<Map.Entry<K, ORID>> dataCacheIterator = OEmptyMapEntryIterator.INSTANCE;

    private OSBTreeCursorBackward(K fromKey, K toKey, boolean fromKeyInclusive, boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (toKey == null) {
        this.toKeyInclusive = true;
      }

    }

    public Map.Entry<K, ORID> next(int prefetchSize) {
      if (dataCacheIterator == null) {
        return null;
      }

      if (dataCacheIterator.hasNext()) {
        final Map.Entry<K, ORID> entry = dataCacheIterator.next();
        toKey = entry.getKey();

        toKeyInclusive = false;
        return entry;
      }

      dataCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      atomicOperationsManager.acquireReadLock(OSBTreeMultiValue.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (toKey != null) {
            bucketSearchResult = findBucket(toKey, atomicOperation);
          } else {
            bucketSearchResult = lastItem(atomicOperation);
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return null;
          }

          long pageIndex = bucketSearchResult.getLastPathItem();

          int itemIndex;
          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = toKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex - 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 2;
          }

          while (dataCache.size() < prefetchSize) {
            if (pageIndex == -1) {
              break;
            }

            final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
            try {
              final OSBTreeBucketMultiValue<K> bucket = new OSBTreeBucketMultiValue<>(cacheEntry, keySerializer, encryption);

              if (itemIndex >= bucket.size()) {
                itemIndex = bucket.size() - 1;
              }

              if (itemIndex < 0) {
                pageIndex = bucket.getLeftSibling();
                itemIndex = Integer.MAX_VALUE;
                continue;
              }

              final OSBTreeBucketMultiValue.LeafEntry leafEntry = bucket.getLeafEntry(itemIndex);
              itemIndex--;

              final K key = deserializeKey(leafEntry.key);

              if (toKey != null && (toKeyInclusive ? comparator.compare(key, toKey) > 0 : comparator.compare(key, toKey) >= 0)) {
                continue;
              }

              if (fromKey != null && (fromKeyInclusive ?
                  comparator.compare(key, fromKey) < 0 :
                  comparator.compare(key, fromKey) <= 0)) {
                break;
              }

              for (final ORID rid : leafEntry.values) {
                dataCache.add(new Map.Entry<K, ORID>() {
                  @Override
                  public K getKey() {
                    return key;
                  }

                  @Override
                  public ORID getValue() {
                    return rid;
                  }

                  @Override
                  public ORID setValue(ORID value) {
                    throw new UnsupportedOperationException("setValue");
                  }
                });
              }
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (IOException e) {
        throw OException.wrapException(new OSBTreeMultiValueException("Error during element iteration", OSBTreeMultiValue.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(OSBTreeMultiValue.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return null;
      }

      dataCacheIterator = dataCache.iterator();

      final Map.Entry<K, ORID> entry = dataCacheIterator.next();

      toKey = entry.getKey();
      toKeyInclusive = false;

      return entry;
    }
  }
}