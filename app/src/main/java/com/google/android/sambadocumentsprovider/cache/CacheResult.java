/*
 * Copyright 2017 Google Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.google.android.sambadocumentsprovider.cache;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.core.util.Pools.Pool;
import androidx.core.util.Pools.SimplePool;
import androidx.core.util.Pools.SynchronizedPool;
import com.google.android.sambadocumentsprovider.BuildConfig;
import com.google.android.sambadocumentsprovider.document.DocumentMetadata;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class CacheResult implements AutoCloseable {

  @IntDef({CACHE_MISS, CACHE_HIT, CACHE_EXPIRED})
  @Retention(RetentionPolicy.SOURCE)
  @interface State {}
  public static final int CACHE_MISS = 0;
  public static final int CACHE_HIT = 1;
  public static final int CACHE_EXPIRED = 2;

  private static final Pool<CacheResult> POOL = new SynchronizedPool<>(10);

  private @State int mState;
  private @Nullable DocumentMetadata mItem;

  private CacheResult() {}

  public @State int getState() {
    return mState;
  }

  public DocumentMetadata getItem() {
    return mItem;
  }

  static CacheResult obtain(int state, @Nullable DocumentMetadata item) {
    CacheResult result = POOL.acquire();
    if (result == null) {
      result = new CacheResult();
    }
    result.mState = state;
    result.mItem = item;

    return result;
  }

  public void recycle() {
    mState = CACHE_MISS;
    mItem = null;
    boolean recycled = POOL.release(this);
    if (BuildConfig.DEBUG && !recycled) throw new IllegalStateException("One item is not enough!");
  }

  @Override
  public void close() {
    recycle();
  }
}
