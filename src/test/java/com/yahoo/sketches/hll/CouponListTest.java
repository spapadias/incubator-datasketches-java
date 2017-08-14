/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

/**
 * @author Lee Rhodes
 */
public class CouponListTest {

  @Test
  public void checkIterator() {
    HllSketch sk = new HllSketch(8);
    for (int i = 0; i < 15; i++) { sk.update(i); }
    PairIterator itr = sk.getIterator();
    println(itr.getHeader());
    while (itr.nextAll()) {
      int key = itr.getKey();
      int val = itr.getValue();
      int idx = itr.getIndex();
      println("Idx: " + idx + ", Key: " + key + ", Val: " + val);
    }
  }

  @Test
  public void checkDuplicatesAndMisc() {
    HllSketch sk = new HllSketch(8);
    for (int i = 1; i <= 7; i++) {
      sk.update(i);
      sk.update(i);
    }
    assertEquals(sk.getCurrentMode(), CurMode.LIST);
    sk.getRelErr(1);
    sk.getRelErrFactor(1);
    assertEquals(sk.getCompositeEstimate(), 7.0, 7 * .01);
    sk.update(8);
    sk.update(8);
    assertEquals(sk.getCurrentMode(), CurMode.SET);
    assertEquals(sk.getCompositeEstimate(), 8.0, 8 * .01);
    sk.getRelErr(1);
    sk.getRelErrFactor(1);
    for (int i = 9; i <= 25; i++) {
      sk.update(i);
      sk.update(i);
    }
    assertEquals(sk.getCurrentMode(), CurMode.HLL);
    assertEquals(sk.getCompositeEstimate(), 25.0, 25 * .1);
    sk.getRelErr(1);
    sk.getRelErrFactor(1);
  }

  @Test
  public void toByteArray_Heapify() {
    toByteArrayHeapify(7);
    toByteArrayHeapify(21);
  }

  private static void toByteArrayHeapify(int lgK) {
    HllSketch sk1 = new HllSketch(lgK);

    int u = (lgK < 8) ? 7 : ((1 << (lgK - 3))/4) * 3;
    for (int i = 0; i < u; i++) {
      sk1.update(i);
    }
    double est1 = sk1.getEstimate();
    assertEquals(est1, u, u * 100.0E-6);
    //println("Original\n" + sk1.toString());

    byte[] byteArray = sk1.toCompactByteArray();
    //println("Preamble: " + PreambleUtil.toString(byteArray));
    HllSketch sk2 = HllSketch.heapify(byteArray);
    double est2 = sk2.getEstimate();
    //println("Heapify Compact\n" + sk2.toString(true, true, true, true));
    assertEquals(est2, est1, 0.0);

    byteArray = sk1.toUpdatableByteArray();
    sk2 = HllSketch.heapify(byteArray);
    est2 = sk2.getEstimate();
    //println("Heapify Updatable\n" + sk2.toString());
    assertEquals(est2, est1, 0.0);
  }

  @Test
  public void printlnTest() {
    println("PRINTING: "+this.getClass().getName());
  }

  /**
   * @param s value to print
   */
  static void println(String s) {
    //System.out.println(s); //disable here
  }
}