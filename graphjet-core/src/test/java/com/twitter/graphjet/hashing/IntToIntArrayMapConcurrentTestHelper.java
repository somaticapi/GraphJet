/**
 * Copyright 2016 Twitter. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.twitter.graphjet.hashing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Lists;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import it.unimi.dsi.fastutil.ints.IntIterator;

public final class IntToIntArrayMapConcurrentTestHelper {

  private IntToIntArrayMapConcurrentTestHelper() {
    // Utility class
  }

  /**
   * Helper class to allow reading from a {@link IntToIntArrayMap} in a controlled manner.
   */
  public static class IntToIntArrayMapReader implements Runnable {
    private final IntToIntArrayMap intToIntArrayMap;
    private final CountDownLatch startSignal;
    private final CountDownLatch doneSignal;
    private final int key;
    private final int sleepTimeInMilliseconds;
    private ArrayList<Integer> value;

    public IntToIntArrayMapReader(
      IntToIntArrayMap intToIntArrayMap,
      CountDownLatch startSignal,
      CountDownLatch doneSignal,
      int key,
      int sleepTimeInMilliseconds) {
      this.intToIntArrayMap = intToIntArrayMap;
      this.startSignal = startSignal;
      this.doneSignal = doneSignal;
      this.key = key;
      this.sleepTimeInMilliseconds = sleepTimeInMilliseconds;
      this.value = new ArrayList<Integer>();
    }

    @Override
    public void run() {
      try {
        startSignal.await();
        Thread.sleep(sleepTimeInMilliseconds);
      } catch (InterruptedException e) {
        throw new RuntimeException("Unable to start waiting: ", e);
      }
      IntIterator iter = intToIntArrayMap.get(key);
      while (iter.hasNext()) {
        value.add(iter.next());
      }
      doneSignal.countDown();
    }

    public ArrayList<Integer> getValue() {
      return value;
    }
  }

  /**
   * Helper class to allow writing to a {@link LongToInternalIntBiMap} in a controlled manner.
   */
  public static class IntToIntArrayMapWriter implements Runnable {
    private final IntToIntArrayMap intToIntArrayMap;
    private final MapWriterInfo mapWriterInfo;

    public IntToIntArrayMapWriter(
      IntToIntArrayMap intToIntArrayMap, MapWriterInfo mapWriterInfo) {
      this.intToIntArrayMap = intToIntArrayMap;
      this.mapWriterInfo = mapWriterInfo;
    }

    @Override
    public void run() {
      Iterator<Map.Entry<Integer, ArrayList<Integer>>> iterator =
        mapWriterInfo.entries.entrySet().iterator();
      while (iterator.hasNext()) {
        try {
          mapWriterInfo.startSignal.await();
        } catch (InterruptedException e) {
          throw new RuntimeException("Interrupted while waiting: ", e);
        }
        Map.Entry<Integer, ArrayList<Integer>> entry = iterator.next();

        int[] value = null;

        if (entry.getValue() != null) {
          value = new int[entry.getValue().size()];
          for (int i = 0; i < entry.getValue().size(); i++) {
            value[i] = entry.getValue().get(i);
          }
        }

        intToIntArrayMap.put(entry.getKey(), value);
        mapWriterInfo.doneSignal.countDown();
      }
    }
  }

  /**
   * This class encapsulates information needed by a writer to add to a map.
   */
  public static class MapWriterInfo {
    private final Map<Integer, ArrayList<Integer>> entries;
    private final CountDownLatch startSignal;
    private final CountDownLatch doneSignal;

    public MapWriterInfo(
      Map<Integer, ArrayList<Integer>> entries,
      CountDownLatch startSignal,
      CountDownLatch doneSignal
    ) {
      this.entries = entries;
      this.startSignal = startSignal;
      this.doneSignal = doneSignal;
    }
  }

  /**
   * This helper method sets up a concurrent read-write situation with a single writer and multiple
   * readers that access the same underlying map, and tests for correct recovery of entries after
   * every single entry insertion, via the use of latches. This helps test write flushing after
   * every entry insertion.
   *
   * @param map     is the underlying {@link IntToIntArrayMap}
   * @param keysToValueMap contains all the keysAndValues to add to the map
   */
  public static void testConcurrentReadWrites(
    IntToIntArrayMap map,
    Map<Integer, ArrayList<Integer>> keysToValueMap
  ) {
    int numReaders = keysToValueMap.size(); // start reading after first edge is written
    ExecutorService executor = Executors.newFixedThreadPool(numReaders + 1); // single writer

    List<CountDownLatch> readerStartLatches = Lists.newArrayListWithCapacity(numReaders);
    List<CountDownLatch> readerDoneLatches = Lists.newArrayListWithCapacity(numReaders);
    List<IntToIntArrayMapReader> readers = Lists.newArrayListWithCapacity(numReaders);

    Iterator<Map.Entry<Integer, ArrayList<Integer>>> iterator =
      keysToValueMap.entrySet().iterator();

    while (iterator.hasNext()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(1);
      // Each time, get edges for the node added in the previous step
      IntToIntArrayMapReader mapReader =
        new IntToIntArrayMapReader(
          map,
          startLatch,
          doneLatch,
          iterator.next().getKey(),
          0);
      readers.add(mapReader);
      executor.submit(mapReader);
      readerStartLatches.add(startLatch);
      readerDoneLatches.add(doneLatch);
    }

    Iterator<Map.Entry<Integer, ArrayList<Integer>>> writerIterator =
      keysToValueMap.entrySet().iterator();
    /**
     * The start/done latches achieve the following execution order: writer, then reader 1, then
     * writer, then reader 2, and so on. As a concrete example, suppose we have two readers and a
     * writer, then the start/done latches are used as follows:
     * Initial latches state:
     * s1 = 1, d1 = 1
     * s2 = 1, d2 = 1
     * Execution steps:
     * - writer writes edge 1, sets s1 = 0 and waits on d1
     * - reader 1 reads since s1 == 0 and sets d1 = 0
     * - writer writes edge 2, sets s2 = 0 and waits on d2
     * - reader 2 reads since s2 == 0 and sets d2 = 0
     */
    for (int i = 0; i < numReaders; i++) {
      // Start writing immediately at first, but then write an edge once the reader finishes reading
      // the previous edge
      CountDownLatch startLatch = (i > 0) ? readerDoneLatches.get(i - 1) : new CountDownLatch(0);
      // Release the next reader
      CountDownLatch doneLatch = readerStartLatches.get(i);

      Map.Entry<Integer, ArrayList<Integer>> entry = writerIterator.next();
      Map<Integer, ArrayList<Integer>> writerKeysToValueMap =
        new TreeMap<Integer, ArrayList<Integer>>();
      writerKeysToValueMap.put(entry.getKey(), entry.getValue());

      executor.submit(
        new IntToIntArrayMapWriter(
          map,
          new MapWriterInfo(writerKeysToValueMap, startLatch, doneLatch))
      );
    }

    // Wait for all the processes to finish and then confirm that they did what they worked as
    // expected
    try {
      readerDoneLatches.get(numReaders - 1).await();
    } catch (InterruptedException e) {
      throw new RuntimeException("Execution for last reader was interrupted: ", e);
    }

    // Check that all readers' read info is consistent with the map
    for (IntToIntArrayMapReader reader : readers) {
      IntIterator iter = map.get(reader.key);
      ArrayList<Integer> expectedValue = new ArrayList<Integer>();
      while (iter.hasNext()) {
        expectedValue.add(iter.next());
      }
      // Check we get the right value
      assertTrue(reader.getValue().equals(expectedValue));
    }
  }

  /**
   * This helper method sets up a concurrent read-write situation with a single writer and multiple
   * readers that access the same underlying LongToInternalIntBiMap, and tests for correct entry
   * access during simultaneous entry reads. This helps test read consistency during arbitrary
   * points of inserting entries. Note that the exact read-write sequence here is non-deterministic
   * and would vary depending on the machine, but the hope is that given the large number of readers
   * the reads would be done at many different points of edge insertion. The test itself checks only
   * for partial correctness (it could have false positives) so this should only be used as a
   * supplement to other testing.
   *
   * @param map                     is the underlying {@link IntToIntArrayMap}
   * @param defaultValue            is the default value returned by the map for a non-entry
   * @param numReaders              is the number of reader threads to use
   * @param keysToValueMap          contains all the keysAndValues to add to the map
   * @param random                  is the random number generator to use
   */
  public static void testRandomConcurrentReadWriteThreads(
    IntToIntArrayMap map,
    ArrayList<Integer> defaultValue,
    int numReaders,
    Map<Integer, ArrayList<Integer>> keysToValueMap,
    Random random) {
    int maxWaitingTimeForThreads = 100; // in milliseconds
    CountDownLatch readersDoneLatch = new CountDownLatch(numReaders);
    List<IntToIntArrayMapReader> readers = Lists.newArrayListWithCapacity(numReaders);

    // Create a bunch of readers that'll read from the map at random
    Iterator<Map.Entry<Integer, ArrayList<Integer>>> iterator =
      keysToValueMap.entrySet().iterator();

    for (int i = 0; i < numReaders; i++) {
      readers.add(new IntToIntArrayMapReader(
        map,
        new CountDownLatch(0),
        readersDoneLatch,
        iterator.next().getKey(),
        random.nextInt(maxWaitingTimeForThreads)));
    }

    // Create a single writer that will insert these edges in random order
    CountDownLatch writerDoneLatch = new CountDownLatch(keysToValueMap.size());
    MapWriterInfo mapWriterInfo =
      new MapWriterInfo(keysToValueMap, new CountDownLatch(0), writerDoneLatch);

    ExecutorService executor =
      Executors.newFixedThreadPool(numReaders + 1); // single writer
    List<Callable<Integer>> allThreads = Lists.newArrayListWithCapacity(numReaders + 1);
    // First, we add the writer
    allThreads.add(Executors.callable(
      new IntToIntArrayMapWriter(map, mapWriterInfo), 1));
    // then the readers
    for (int i = 0; i < numReaders; i++) {
      allThreads.add(Executors.callable(readers.get(i), 1));
    }
    // these will execute in some non-deterministic order
    Collections.shuffle(allThreads, random);

    // Wait for all the processes to finish
    try {
      List<Future<Integer>> results = executor.invokeAll(allThreads, 10, TimeUnit.SECONDS);
      for (Future<Integer> result : results) {
        assertTrue(result.isDone());
        assertEquals(1, result.get().intValue());
      }
    } catch (InterruptedException e) {
      throw new RuntimeException("Execution for a thread was interrupted: ", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Execution issue in an executor thread: ", e);
    }

    // confirm that these worked as expected
    try {
      readersDoneLatch.await();
      writerDoneLatch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException("Execution for last reader was interrupted: ", e);
    }

    // Check that all readers' read info is consistent with the map
    for (IntToIntArrayMapReader reader : readers) {
      IntIterator iter = map.get(reader.key);
      ArrayList<Integer> expectedValue = new ArrayList<Integer>();
      while (iter.hasNext()) {
        expectedValue.add(iter.next());
      }
      // either the entry was not written at the time it was read or we get the right value
      assertTrue((reader.getValue().equals(defaultValue))
        || (reader.getValue().equals(expectedValue)));
    }
  }
}
