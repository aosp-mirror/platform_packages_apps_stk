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

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.util.TelephonyUtils;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * Application installer for SIM Toolkit.
 *
 */
final class StkAppInstaller {
    private static final boolean DBG = TelephonyUtils.IS_DEBUGGABLE;
    private static final String LOG_TAG =
            new Object(){}.getClass().getEnclosingClass().getSimpleName();

    private StkAppInstaller() {
    }

    static void installOrUpdate(Context context, String label) {
        IPackageManager pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (pm != null) {
            ComponentName component = new ComponentName(context, StkMain.class);
            int userId = context.getUserId();
            int icon = R.drawable.ic_launcher_sim_toolkit;
            try {
                try {
                    if (label != null) {
                        pm.overrideLabelAndIcon(component, label, icon, userId);
                    } else {
                        pm.restoreLabelAndIcon(component, userId);
                    }
                    if (DBG) CatLog.d(LOG_TAG, "Set the label to " + label);
                } catch (SecurityException e) {
                    CatLog.e(LOG_TAG, "Failed to set the label to " + label);
                }
                setAppState(pm, component, userId, true);
            } catch (RemoteException e) {
                CatLog.e(LOG_TAG, "Failed to enable SIM Toolkit");
            }
        }
    }

    static void uninstall(Context context) {
        IPackageManager pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (pm != null) {
            ComponentName component = new ComponentName(context, StkMain.class);
            try {
                setAppState(pm, component, context.getUserId(), false);
            } catch (RemoteException e) {
                CatLog.e(LOG_TAG, "Failed to disable SIM Toolkit");
            }
        }
    }

    static void setAppState(IPackageManager pm, ComponentName component, int userId, boolean enable)
            throws RemoteException {
        int current = pm.getComponentEnabledSetting(component, userId);
        int expected = enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (current != expected) {
            pm.setComponentEnabledSetting(component, expected, PackageManager.DONT_KILL_APP,
                    userId);
            if (DBG) CatLog.d(LOG_TAG, "SIM Toolkit is " + (enable ? "enabled" : "disabled"));
        }
    }
}
