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

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;
import android.os.SystemProperties;

/**
 * Application installer for SIM Toolkit.
 *
 */
abstract class StkAppInstaller {
    private static final String STK_MAIN_ACTIVITY = "com.android.stk.StkMain";
    private static final String LOG_TAG = "StkAppInstaller";

    private StkAppInstaller() {
        CatLog.d(LOG_TAG, "init");
    }

    public static void install(Context context) {
        setAppState(context, true);
    }

    public static void unInstall(Context context) {
        setAppState(context, false);
    }

    private static void setAppState(Context context, boolean install) {
        CatLog.d(LOG_TAG, "[setAppState]+");
        if (context == null) {
            CatLog.d(LOG_TAG, "[setAppState]- no context, just return.");
            return;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            CatLog.d(LOG_TAG, "[setAppState]- no package manager, just return.");
            return;
        }
        ComponentName cName = new ComponentName("com.android.stk", STK_MAIN_ACTIVITY);
        int state = install ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        if (((PackageManager.COMPONENT_ENABLED_STATE_ENABLED == state) &&
                (PackageManager.COMPONENT_ENABLED_STATE_ENABLED ==
                pm.getComponentEnabledSetting(cName))) ||
                ((PackageManager.COMPONENT_ENABLED_STATE_DISABLED == state) &&
                (PackageManager.COMPONENT_ENABLED_STATE_DISABLED ==
                pm.getComponentEnabledSetting(cName)))) {
            CatLog.d(LOG_TAG, "Need not change app state!!");
        } else {
            CatLog.d(LOG_TAG, "Change app state[" + install + "]");
            try {
                pm.setComponentEnabledSetting(cName, state, PackageManager.DONT_KILL_APP);
            } catch (Exception e) {
                CatLog.d(LOG_TAG, "Could not change STK app state");
            }
        }
        CatLog.d(LOG_TAG, "[setAppState]-");
    }
}
