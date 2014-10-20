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

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/**
 * AlretDialog used for DISPLAY TEXT commands.
 *
 */
public class StkDialogActivity extends Activity implements View.OnClickListener {
    // members
    private static final String className = new Object(){}.getClass().getEnclosingClass().getName();
    private static final String LOG_TAG = className.substring(className.lastIndexOf('.') + 1);
    TextMessage mTextMsg = null;
    private int mSlotId = -1;
    private StkAppService appService = StkAppService.getInstance();
    private boolean mIsResponseSent = false;

    Handler mTimeoutHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case MSG_ID_TIMEOUT:
                CatLog.d(LOG_TAG, "MSG_ID_TIMEOUT finish.");
                sendResponse(StkAppService.RES_ID_TIMEOUT);
                finish();
                break;
            }
        }
    };

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

        CatLog.d(LOG_TAG, "onCreate");
        initFromIntent(getIntent());
        if (mTextMsg == null) {
            finish();
            return;
        }

        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        Window window = getWindow();

        setContentView(R.layout.stk_msg_dialog);
        TextView mMessageView = (TextView) window
                .findViewById(R.id.dialog_message);

        Button okButton = (Button) findViewById(R.id.button_ok);
        Button cancelButton = (Button) findViewById(R.id.button_cancel);

        okButton.setOnClickListener(this);
        cancelButton.setOnClickListener(this);

        setTitle(mTextMsg.title);
        if (!(mTextMsg.iconSelfExplanatory && mTextMsg.icon != null)) {
            mMessageView.setText(mTextMsg.text);
        }

        if (mTextMsg.icon == null) {
            CatLog.d(LOG_TAG, "onCreate icon is null");
            window.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON,
                    com.android.internal.R.drawable.stat_notify_sim_toolkit);
        } else {
            window.setFeatureDrawable(Window.FEATURE_LEFT_ICON,
                    new BitmapDrawable(mTextMsg.icon));
        }
    }

    public void onClick(View v) {
        String input = null;

        switch (v.getId()) {
        case OK_BUTTON:
            CatLog.d(LOG_TAG, "OK Clicked!, mSlotId: " + mSlotId);
            sendResponse(StkAppService.RES_ID_CONFIRM, true);
            cancelTimeOut();
            finish();
            break;
        case CANCEL_BUTTON:
            CatLog.d(LOG_TAG, "Cancel Clicked!, mSlotId: " + mSlotId);
            sendResponse(StkAppService.RES_ID_CONFIRM, false);
            cancelTimeOut();
            finish();
            break;
        }
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
        /*
         * The user should be shown the message forever or until some high
         * priority event occurs (such as incoming call, MMI code execution
         * etc as mentioned in ETSI 102.223, 6.4.1).
         *
         * Since mTextMsg.responseNeeded is false (because the response has
         * already been sent) and duration of the dialog is zero and userClear
         * is true, don't set the timeout.
         */
        if (!mTextMsg.responseNeeded &&
                StkApp.calculateDurationInMilis(mTextMsg.duration) == 0 &&
                mTextMsg.userClear) {
            CatLog.d(this, "User should clear text..show message forever");
            return;
        }

        startTimeOut(mTextMsg.userClear);
    }

    @Override
    public void onPause() {
        super.onPause();
        CatLog.d(LOG_TAG, "onPause, sim id: " + mSlotId);
        cancelTimeOut();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsResponseSent = false;
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
            CatLog.d(LOG_TAG, "finish.");
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

        Bundle args = new Bundle();
        args.putInt(StkAppService.OPCODE, StkAppService.OP_RESPONSE);
        args.putInt(StkAppService.SLOT_ID, mSlotId);
        args.putInt(StkAppService.RES_ID, resId);
        args.putBoolean(StkAppService.CONFIRMATION, confirmed);
        startService(new Intent(this, StkAppService.class).putExtras(args));
        mIsResponseSent = true;
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
        mTimeoutHandler.removeMessages(MSG_ID_TIMEOUT);
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
            // userClear = false. will dissapear after a while.
            if (dialogDuration == 0) {
                if (waitForUserToClear) {
                    dialogDuration = StkApp.DISP_TEXT_WAIT_FOR_USER_TIMEOUT;
                } else {
                    dialogDuration = StkApp.DISP_TEXT_CLEAR_AFTER_DELAY_TIMEOUT;
                }
            }
            CatLog.d(LOG_TAG, "startTimeOut: " + mSlotId);
            mTimeoutHandler.sendMessageDelayed(mTimeoutHandler
                .obtainMessage(MSG_ID_TIMEOUT), dialogDuration);
        }
    }
}
