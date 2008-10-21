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

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.CheckBoxPreference;
import com.android.internal.telephony.gsm.stk.Service;

/**
 * This class controls the UI for STK global settings.
 */
public class StkSettings extends PreferenceActivity {
    
    // members
    private Preference mServiceName = null;
    private CheckBoxPreference mButtonOnOff = null;
    private Service mStkService = null;
    private static boolean mFirstCreate = true;
    private static int mAppState;

    //String keys for preference lookup
    private static final String SERVICE_NAME  = "service_name";
    private static final String TOGGLE_BUTTON  = "stk_app_enable_disable";

    private static final int APP_ON = 1;
    private static final int APP_OFF = 2;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        // set the preference layout.
        addPreferencesFromResource(R.xml.stk_settings);
        
        // Initialize members.
        mStkService = Service.getInstance();
        if (mStkService == null) {
            finish();
            return;
        }
        PreferenceScreen prefSet = getPreferenceScreen();
        mServiceName  = prefSet.findPreference(SERVICE_NAME);
        mButtonOnOff  = (CheckBoxPreference) prefSet.findPreference(TOGGLE_BUTTON);
        
        // Synchronize activity UI state with STK service.
        refreshUiState();
        mFirstCreate = false;
    }
    
    // Click listener for all toggle events
    public boolean onPreferenceTreeClick(PreferenceScreen preferences, Preference preference) {
        if (preference instanceof CheckBoxPreference) {
            CheckBoxPreference tp = (CheckBoxPreference) preference;

            if (tp == mButtonOnOff) {
                if (tp.isChecked()) {
                    mAppState = APP_ON;
                    sendBroadcast(new Intent("com.android.stk.action.INSTALL"));
                    //StkInstaller.installApp(this);
                } else {
                    mAppState = APP_OFF;
                    sendBroadcast(new Intent("com.android.stk.action.INSTALL"));
                    //StkInstaller.unInstallApp(this);
                }
                return true;
            }
        }
        return false;
    }

    // This function update the UI state according the the Service or package 
    // manager state. ON the first time it is called the state should fit the 
    // service. Sequential calls update the ui according to the state of the 
    /// package manager.  
    private void refreshUiState() {
        if (!mFirstCreate) {
            PackageManager pm = this.getPackageManager();
            if (pm == null) return;
    
            // Set application state.
            setAppState(mAppState == APP_ON);
        } else {
            boolean enabled = mStkService.isStkSupported();
            mAppState = enabled ? APP_ON : APP_OFF;
            setAppState(enabled);
        }
    }

    private void setAppState(boolean enabled) {
        if (enabled) {
            String name = mStkService.getServiceName();
            mServiceName.setSummary(mStkService.getServiceName());
            mButtonOnOff.setChecked(true);
        } else {
            mServiceName.setSummary(R.string.stk_no_service);
            mButtonOnOff.setChecked(false);
        }
    }
}
