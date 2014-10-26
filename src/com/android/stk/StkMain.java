/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.PhoneConstants;

import android.telephony.TelephonyManager;

import android.view.Gravity;
import android.widget.Toast;

/**
 * Launcher class. Serve as the app's MAIN activity, send an intent to the
 * StkAppService and finish.
 *
 */
 public class StkMain extends Activity {
    private static final String className = new Object(){}.getClass().getEnclosingClass().getName();
    private static final String LOG_TAG = className.substring(className.lastIndexOf('.') + 1);
    private int mSingleSimId = -1;
    private Context mContext = null;
    private TelephonyManager mTm = null;
    private static final String PACKAGE_NAME = "com.android.stk";
    private static final String STK_LAUNCHER_ACTIVITY_NAME = PACKAGE_NAME + ".StkLauncherActivity";

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        CatLog.d(LOG_TAG, "onCreate+");
        mContext = getBaseContext();
        mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        //Check if needs to show the meun list.
        if (isShowSTKListMenu()) {
            Intent newIntent = new Intent(Intent.ACTION_VIEW);
            newIntent.setClassName(PACKAGE_NAME, STK_LAUNCHER_ACTIVITY_NAME);
            startActivity(newIntent);
        } else {
            //launch stk menu activity for the SIM.
            if (mSingleSimId < 0) {
                showTextToast(mContext, R.string.no_sim_card_inserted);
            } else {
                launchSTKMainMenu(mSingleSimId);
            }
        }
        finish();
    }

    private boolean isShowSTKListMenu() {
        int simCount = TelephonyManager.from(mContext).getSimCount();
        int simInsertedCount = 0;
        int insertedSlotId = -1;

        CatLog.d(LOG_TAG, "simCount: " + simCount);
        for (int i = 0; i < simCount; i++) {
            //Check if the card is inserted.
            if (mTm.hasIccCard(i)) {
                CatLog.d(LOG_TAG, "SIM " + i + " is inserted.");
                mSingleSimId = i;
                simInsertedCount++;
            } else {
                CatLog.d(LOG_TAG, "SIM " + i + " is not inserted.");
            }
        }
        if (simInsertedCount > 1) {
            return true;
        } else {
            //No card or only one card.
            CatLog.d(LOG_TAG, "do not show stk list menu.");
            return false;
        }
    }

    private void launchSTKMainMenu(int slotId) {
        Bundle args = new Bundle();
        CatLog.d(LOG_TAG, "launchSTKMainMenu.");
        args.putInt(StkAppService.OPCODE, StkAppService.OP_LAUNCH_APP);
        args.putInt(StkAppService.SLOT_ID
                , PhoneConstants.SIM_ID_1 + slotId);
        startService(new Intent(this, StkAppService.class)
                .putExtras(args));
    }

    private void showTextToast(Context context, int resId) {
        Toast toast = Toast.makeText(context, resId, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }
}
