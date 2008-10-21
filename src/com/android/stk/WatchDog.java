/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.stk;

import android.os.Handler;
import android.util.Log;

/**
 * This interface defines Event to be used by WatchDog.
 * Watchdog expires when event is set.
 */
interface Event {

    public void set();
    
    public void unSet();

    public boolean isSet();
}

/**
 * This class implements Watch Dog using a daemon thread.
 * WatchDog waits for an event to expire and execute Runnable in the caller 
 * context.
 */
class WatchDog extends Thread {
    // Constants
    private static final int POLLING_INTERVAL = 150;
    public static final int TIMEOUT_WAIT_FOREVER = Integer.MAX_VALUE;

    private static final String TAG = "WatchDog";
    private static final boolean DBG = true;

    // Members
    private Event mEvent = null;  
    private Handler mCaller = null;
    private Runnable mRunnable = null;
    private int mTimeout = TIMEOUT_WAIT_FOREVER;
    private int mInterval = POLLING_INTERVAL;
    private int mElapsedTime = 0;
    private Object mElapsedTimeLock = new Object();
    private boolean mCanceled = false;
    private boolean mPaused = false; 

    class VoidEvent implements Event {
        public void set() {}
        
        public void unSet() {}

        public boolean isSet() {
            return false;
        }
    }

    WatchDog(Event event, Handler caller, Runnable onEventResponse, int timeout) {
        mEvent = event == null ? new VoidEvent() : event;
        mCaller = caller;
        mRunnable = onEventResponse;
        mTimeout = timeout;

        this.setDaemon(true);
        this.start();
    }

    public void cancel() {
        mTimeout = 0;
        mCanceled = true;
    }

    public void reset() {
        synchronized (mElapsedTimeLock) {
            mElapsedTime = 0;
        }
    }
    
    public void pause() {
        mPaused = true;
    }
    
    public void unpause() {
        mPaused = false;
    }

    @Override
    public void run() {
        watchEvent();
        // When event expires post response to caller.
        if (mCaller != null && mRunnable != null) {
            if (!mCanceled) {
                mCaller.post(mRunnable);
            }
        }
    }

    // Synchronized method, block until event expires.
    private void watchEvent() {
        while (!isEventSet() && !isTimedOut()) {
            try {
                Thread.sleep(mInterval);
                incrementTime();
            } catch (InterruptedException ie) {
                if (DBG) Log.d(TAG, ie.toString());
            }
        }
    }

    private void incrementTime() {
        if (mPaused) {
            return;
        }
        synchronized (mElapsedTimeLock) {
            mElapsedTime += (mTimeout == TIMEOUT_WAIT_FOREVER ? 0 : mInterval);
        }
    }

    private boolean isTimedOut() {
        if (mPaused) {
            return false;
        }
        return (mElapsedTime >= mTimeout);
    }

    private boolean isEventSet() {
        if (mPaused) {
            return false;
        }
        return mEvent.isSet();
    }
}
