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

import android.app.Activity;
import android.app.Application;
import com.android.internal.telephony.gsm.stk.Duration;

/**
 * Top-level Application class for the Phone app.
 */
public class StkApp extends Application {
    // Application constants
    public static final boolean DBG = true;

    // Result values for sub activities started by the main StkActivity.
    static final int RESULT_OK = Activity.RESULT_OK;
    static final int RESULT_TIMEDOUT = RESULT_OK + 10;
    static final int RESULT_BACKWARD = RESULT_OK + 11;
    static final int RESULT_HELP = RESULT_OK + 12;
    static final int RESULT_END_SESSION = RESULT_OK + 20;

    // Identifiers for option menu items
    static final int MENU_ID_MAIN = android.view.Menu.FIRST;
    static final int MENU_ID_HELP = android.view.Menu.FIRST + 1;

    // UI timeout, 30 seconds - used for display dialog and activities.
    static final int UI_TIMEOUT = (20 * 1000);

    // Tone default timeout - 2 seconds
    static final int TONE_DFEAULT_TIMEOUT = (2 * 1000);

    /**
     * This function calculate the time in MS a tone should be played.
     */
    public static int calculateToneDuration(Duration duration) {
        int timeout = TONE_DFEAULT_TIMEOUT;
        if (duration != null) {
            switch (duration.timeUnit) {
            case MINUTE:
                timeout = 1000 * 60;
                break;
            case TENTH_SECOND:
                timeout = 1000 * 10;
                break;
            case SECOND:
            default:
                timeout = 1000;
                break;
            }
            timeout *= duration.timeInterval;
        }
        return timeout;
    }
}
