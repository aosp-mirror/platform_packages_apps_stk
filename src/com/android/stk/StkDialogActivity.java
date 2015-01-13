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
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.cat.CatLog;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/**
 * AlertDialog used for DISPLAY TEXT commands.
 *
 */
public class StkDialogActivity extends Activity implements View.OnClickListener {
    // members
    private static final String className = new Object(){}.getClass().getEnclosingClass().getName();
    private static final String LOG_TAG = className.substring(className.lastIndexOf('.') + 1);
    TextMessage mTextMsg = null;
    private int mSlotId = -1;
    private StkAppService appService = StkAppService.getInstance();
    // Determines whether Terminal Response (TR) has been sent
    private boolean mIsResponseSent = false;
    private Context mContext;
    // Utilize AlarmManager for real-time countdown
    private PendingIntent mTimeoutIntent;
    private AlarmManager mAlarmManager;
    private final static String ALARM_TIMEOUT = "com.android.stk.DIALOG_ALARM_TIMEOUT";

    //keys) for saving the state of the dialog in the icicle
    private static final String TEXT = "text";

    // message id for time out
    private static final int MSG_ID_TIMEOUT = 1;

    // buttons id
    public static final int OK_BUTTON = R.id.button_ok;
    public static final int CANCEL_BUTTON = R.id.button_cancel;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        CatLog.d(LOG_TAG, "onCreate, sim id: " + mSlotId);
        // New Dialog is created - set to no response sent
        mIsResponseSent = false;

        requestWindowFeature(Window.FEATURE_LEFT_ICON);

        setContentView(R.layout.stk_msg_dialog);

        Button okButton = (Button) findViewById(R.id.button_ok);
        Button cancelButton = (Button) findViewById(R.id.button_cancel);

        okButton.setOnClickListener(this);
        cancelButton.setOnClickListener(this);

        mContext = getBaseContext();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_TIMEOUT);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        mAlarmManager =(AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);

    }

    public void onClick(View v) {
        String input = null;
        switch (v.getId()) {
        case OK_BUTTON:
            CatLog.d(LOG_TAG, "OK Clicked!, mSlotId: " + mSlotId);
            cancelTimeOut();
            sendResponse(StkAppService.RES_ID_CONFIRM, true);
            break;
        case CANCEL_BUTTON:
            CatLog.d(LOG_TAG, "Cancel Clicked!, mSlotId: " + mSlotId);
            cancelTimeOut();
            sendResponse(StkAppService.RES_ID_CONFIRM, false);
            break;
        }
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            CatLog.d(LOG_TAG, "onKeyDown - KEYCODE_BACK");
            cancelTimeOut();
            sendResponse(StkAppService.RES_ID_BACKWARD);
            finish();
            break;
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        CatLog.d(LOG_TAG, "onResume - mIsResponseSent[" + mIsResponseSent +
                "], sim id: " + mSlotId);

        initFromIntent(getIntent());
        if (mTextMsg == null) {
            finish();
            return;
        }

        Window window = getWindow();

        TextView mMessageView = (TextView) window
                .findViewById(R.id.dialog_message);

        setTitle(mTextMsg.title);

        if (!(mTextMsg.iconSelfExplanatory && mTextMsg.icon != null)) {
            mMessageView.setText(mTextMsg.text);
        }

        if (mTextMsg.icon == null) {
            window.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON,
                    com.android.internal.R.drawable.stat_notify_sim_toolkit);
        } else {
            window.setFeatureDrawable(Window.FEATURE_LEFT_ICON,
                    new BitmapDrawable(mTextMsg.icon));
        }

        /*
         * If the userClear flag is set and dialogduration is set to 0, the display Text
         * should be displayed to user forever until some high priority event occurs
         * (incoming call, MMI code execution etc as mentioned under section
         * ETSI 102.223, 6.4.1)
         */
        if (StkApp.calculateDurationInMilis(mTextMsg.duration) == 0 &&
            !mTextMsg.responseNeeded && mTextMsg.userClear) {
            CatLog.d(LOG_TAG, "User should clear text..showing message forever");
            return;
        }

        appService.setDisplayTextDlgVisibility(true, mSlotId);

        /*
         * When another activity takes the foreground, we do not want the Terminal
         * Response timer to be restarted when our activity resumes. Hence we will
         * check if there is an existing timer, and resume it. In this way we will
         * inform the SIM in correct time when there is no response from the User 
         * to a dialog.
         */
        if (mTimeoutIntent != null) {
            CatLog.d(LOG_TAG, "Pending Alarm! Let it finish counting down...");
        }
        else {
            CatLog.d(LOG_TAG, "No Pending Alarm! OK to start timer...");
            startTimeOut(mTextMsg.userClear);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        CatLog.d(LOG_TAG, "onPause, sim id: " + mSlotId);
        appService.setDisplayTextDlgVisibility(false, mSlotId);

        /*
         * do not cancel the timer here cancelTimeOut(). If any higher/lower
         * priority events such as incoming call, new sms, screen off intent,
         * notification alerts, user actions such as 'User moving to another activtiy'
         * etc.. occur during Display Text ongoing session,
         * this activity would receive 'onPause()' event resulting in
         * cancellation of the timer. As a result no terminal response is
         * sent to the card.
         */
    }

    @Override
    protected void onStart() {
        CatLog.d(LOG_TAG, "onStart, sim id: " + mSlotId);
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        CatLog.d(LOG_TAG, "onStop - before Send CONFIRM false mIsResponseSent[" +
                mIsResponseSent + "], sim id: " + mSlotId);
        if (!mIsResponseSent) {
            appService.getStkContext(mSlotId).setPendingDialogInstance(this);
        } else {
            CatLog.d(LOG_TAG, "finish.");
            appService.getStkContext(mSlotId).setPendingDialogInstance(null);
            cancelTimeOut();
            finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        CatLog.d(LOG_TAG, "onDestroy - mIsResponseSent[" + mIsResponseSent +
                "], sim id: " + mSlotId);
        // if dialog activity is finished by stkappservice
        // when receiving OP_LAUNCH_APP from the other SIM, we can not send TR here
        // , since the dialog cmd is waiting user to process.
        if (!mIsResponseSent && !appService.isDialogPending(mSlotId)) {
            sendResponse(StkAppService.RES_ID_CONFIRM, false);
        }
        cancelTimeOut();
        // Cleanup broadcast receivers to avoid leaks
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        CatLog.d(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(outState);

        outState.putParcelable(TEXT, mTextMsg);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        mTextMsg = savedInstanceState.getParcelable(TEXT);
        CatLog.d(LOG_TAG, "onRestoreInstanceState - [" + mTextMsg + "]");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        CatLog.d(LOG_TAG, "onNewIntent - updating the same Dialog box");
        setIntent(intent);
    }

    private void sendResponse(int resId, boolean confirmed) {
        if (mSlotId == -1) {
            CatLog.d(LOG_TAG, "sim id is invalid");
            return;
        }

        if (StkAppService.getInstance() == null) {
            CatLog.d(LOG_TAG, "Ignore response: id is " + resId);
            return;
        }

        CatLog.d(LOG_TAG, "sendResponse resID[" + resId + "] confirmed[" + confirmed + "]");

        if (mTextMsg.responseNeeded) {
            Bundle args = new Bundle();
            args.putInt(StkAppService.OPCODE, StkAppService.OP_RESPONSE);
            args.putInt(StkAppService.SLOT_ID, mSlotId);
            args.putInt(StkAppService.RES_ID, resId);
            args.putBoolean(StkAppService.CONFIRMATION, confirmed);
            startService(new Intent(this, StkAppService.class).putExtras(args));
            mIsResponseSent = true;
        }
    }

    private void sendResponse(int resId) {
        sendResponse(resId, true);
    }

    private void initFromIntent(Intent intent) {

        if (intent != null) {
            mTextMsg = intent.getParcelableExtra("TEXT");
            mSlotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
        } else {
            finish();
        }

        CatLog.d(LOG_TAG, "initFromIntent - [" + mTextMsg + "], sim id: " + mSlotId);
    }

    private void cancelTimeOut() {
        CatLog.d(LOG_TAG, "cancelTimeOut: " + mSlotId);
        if (mTimeoutIntent != null) {
            mAlarmManager.cancel(mTimeoutIntent);
            mTimeoutIntent = null;
        }
    }

    private void startTimeOut(boolean waitForUserToClear) {

        // Reset timeout.
        cancelTimeOut();
        int dialogDuration = StkApp.calculateDurationInMilis(mTextMsg.duration);
        // If duration is specified, this has priority. If not, set timeout
        // according to condition given by the card.
        if (mTextMsg.userClear == true && mTextMsg.responseNeeded == false) {
            return;
        } else {
            // userClear = false. will disappear after a while.
            if (dialogDuration == 0) {
                if (waitForUserToClear) {
                    dialogDuration = StkApp.DISP_TEXT_WAIT_FOR_USER_TIMEOUT;
                } else {
                    dialogDuration = StkApp.DISP_TEXT_CLEAR_AFTER_DELAY_TIMEOUT;
                }
            }
            CatLog.d(LOG_TAG, "startTimeOut: " + mSlotId);
            Intent mAlarmIntent = new Intent(ALARM_TIMEOUT);
            mAlarmIntent.putExtra(StkAppService.SLOT_ID, mSlotId);
            mTimeoutIntent = PendingIntent.getBroadcast(mContext, 0, mAlarmIntent, PendingIntent.FLAG_CANCEL_CURRENT);

            // Try to use a more stringent timer not affected by system sleep.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + dialogDuration, mTimeoutIntent);
            }
            else {
                mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + dialogDuration, mTimeoutIntent);
            }
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int slotID = intent.getIntExtra(StkAppService.SLOT_ID, 0);

            if (action == null || slotID != mSlotId) return;
            CatLog.d(LOG_TAG, "onReceive, action=" + action + ", sim id: " + slotID);
            if (action.equals(ALARM_TIMEOUT)) {
                CatLog.d(LOG_TAG, "ALARM_TIMEOUT rcvd");
                mTimeoutIntent = null;
                sendResponse(StkAppService.RES_ID_TIMEOUT);
                finish();
            }
        }
    };
}
