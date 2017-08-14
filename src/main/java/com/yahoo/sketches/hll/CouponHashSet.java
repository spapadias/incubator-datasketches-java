/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.sketches.hll.HllUtil.EMPTY;
import static com.yahoo.sketches.hll.HllUtil.KEY_MASK_26;
import static com.yahoo.sketches.hll.HllUtil.RESIZE_DENOM;
import static com.yahoo.sketches.hll.HllUtil.RESIZE_NUMER;
import static com.yahoo.sketches.hll.PreambleUtil.HASH_SET_INT_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.HASH_SET_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.LIST_INT_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.extractCompactFlag;
import static com.yahoo.sketches.hll.PreambleUtil.extractCurMode;
import static com.yahoo.sketches.hll.PreambleUtil.extractHashSetCount;
import static com.yahoo.sketches.hll.PreambleUtil.extractInt;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgArr;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgK;
import static com.yahoo.sketches.hll.PreambleUtil.extractTgtHllType;

import com.yahoo.memory.Memory;
import com.yahoo.memory.WritableMemory;
import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.SketchesStateException;

/**
 * @author Lee Rhodes
 * @author Kevin Lang
 */
class CouponHashSet extends CouponList {

  /**
   * Constructs this sketch with the intent of loading it with data
   * @param lgConfigK the configured Lg K
   * @param tgtHllType the new target Hll type
   */
  CouponHashSet(final int lgConfigK, final TgtHllType tgtHllType) {
    super(lgConfigK, tgtHllType, CurMode.SET);
    assert lgConfigK > 7;
  }

  /**
   * Copy constructor
   * @param that another CouponHashSet
   */
  CouponHashSet(final CouponHashSet that) {
    super(that);
  }

  /**
   * Copy As constructor.
   * @param that another CouponHashSet
   * @param tgtHllType the new target Hll type
   */
  CouponHashSet(final CouponHashSet that, final TgtHllType tgtHllType) {
    super(that, tgtHllType);
  }

  //will also accept List, but results in a Set
  static final CouponHashSet heapifySet(final Memory mem) {
    final Object memObj = ((WritableMemory) mem).getArray();
    final long memAdd = mem.getCumulativeOffset(0);

    final int lgConfigK = extractLgK(memObj, memAdd);
    final TgtHllType tgtHllType = extractTgtHllType(memObj, memAdd);
    final int lgCouponArrInts = extractLgArr(memObj, memAdd);
    final CurMode curMode = extractCurMode(memObj, memAdd);
    final int arrStart = (curMode == CurMode.LIST) ? LIST_INT_ARR_START : HASH_SET_INT_ARR_START;

    final CouponHashSet set = new CouponHashSet(lgConfigK, tgtHllType);
    set.putOutOfOrderFlag(true);
    final boolean compact = extractCompactFlag(memObj, memAdd);
    final int couponCount = extractHashSetCount(memObj, memAdd);
    if (compact) {
      for (int i = 0; i < couponCount; i++) {
        final int coupon = extractInt(memObj, memAdd, arrStart + (i << 2));
        if (coupon == EMPTY) { continue; }
        set.couponUpdate(coupon);
      }
    } else { //updatable
      set.putCouponCount(couponCount);
      set.putLgCouponArrInts(lgCouponArrInts);
      final int couponArrInts = 1 << lgCouponArrInts;
      set.couponIntArr = new int[couponArrInts];
      mem.getIntArray(HASH_SET_INT_ARR_START, set.couponIntArr, 0, couponArrInts);
    }
    return set;
  }

  @Override
  CouponHashSet copy() {
    return new CouponHashSet(this);
  }

  @Override
  CouponHashSet copyAs(final TgtHllType tgtHllType) {
    return new CouponHashSet(this, tgtHllType);
  }

  @Override //get coupons from internal int[] to dstMem
  //Called by CouponList.insertList()
  //Called by CouponList.insertSet()
  void getCouponsToMemoryInts(final WritableMemory dstWmem, final int lenInts) {
    dstWmem.putIntArray(HASH_SET_INT_ARR_START, couponIntArr, 0, lenInts);
  }

  @Override //put coupons from srcMem to internal int[]
  //Called by CouponList.heapifyList()
  void putCouponsFromMemoryInts(final Memory srcMem, final int lenInts) {
    srcMem.getIntArray(HASH_SET_INT_ARR_START, couponIntArr, 0, lenInts);
  }

  @Override
  HllSketchImpl couponUpdate(final int coupon) {
    if (coupon == EMPTY) {
      return this; //empty coupon, ignore
    }
    final int index = find(couponIntArr, lgCouponArrInts, coupon);
    if (index >= 0) {
      return this; //found duplicate, ignore
    }
    couponIntArr[~index] = coupon; //found empty
    couponCount++;
    final boolean promote = checkGrowOrPromote();
    if (!promote) { return this; }
    return promoteHeapListOrSetToHll(this);
  }

  @Override
  int getMemArrStart() {
    return HASH_SET_INT_ARR_START;
  }

  @Override
  int getPreInts() {
    return HASH_SET_PREINTS;
  }

  private boolean checkGrowOrPromote() {
    if ((RESIZE_DENOM * couponCount) > (RESIZE_NUMER * (1 << lgCouponArrInts))) {
      if (lgCouponArrInts == (lgConfigK - 3)) {
        return true; // promote
      }
      //TODO if direct, ask for more memory
      couponIntArr = growHashSet(couponIntArr, ++lgCouponArrInts);
    }
    return false;
  }

  private static final int[] growHashSet(final int[] coupIntArr, final int tgtLgCoupArrSize) {
    final int tgtArrSize = 1 << tgtLgCoupArrSize;
    final int[] tgtCouponIntArr = new int[tgtArrSize]; //create tgt
    final int len = coupIntArr.length;
    for (int i = 0; i < len; i++) { //scan input arr for non-zero values
      final int fetched = coupIntArr[i];
      if (fetched != EMPTY) {
        final int idx = find(tgtCouponIntArr, tgtLgCoupArrSize, fetched); //find empty in tgt
        if (idx < 0) { //found EMPTY
          tgtCouponIntArr[~idx] = fetched; //insert
          continue;
        }
        throw new SketchesStateException("Error: found duplicate.");
      }
    }
    return tgtCouponIntArr;
  }

  //Searches the Coupon hash table for an empty slot or a duplicate depending on the context.
  //If entire entry is empty, returns one's complement of index = found empty.
  //If entry equals given coupon, returns its index = found duplicate coupon
  //Continues searching
  //If the probe comes back to original index, throws an exception.
  //Called by CouponHashSet.couponUpdate()
  //Called by CouponHashSet.growHashSet()
  //Called by DirectCouponHashSet.growHashSet()
  static final int find(final int[] array, final int lgArrInts, final int coupon) {
    final int arrMask = array.length - 1;
    int probe = coupon & arrMask;
    final int loopIndex = probe;
    do {
      final int arrVal = array[probe];
      if (arrVal == EMPTY) { //Compares on entire coupon
        return ~probe; //empty
      }
      else if (coupon == arrVal) { //Compares on entire coupon
        return probe; //duplicate
      }
      final int stride = ((coupon & KEY_MASK_26) >>> lgArrInts) | 1;
      probe = (probe + stride) & arrMask;
    } while (probe != loopIndex);
    throw new SketchesArgumentException("Key not found and no empty slots!");
  }

}