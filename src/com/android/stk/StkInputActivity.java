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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.telephony.CarrierConfigManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.TextView.BufferType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.Input;

import com.google.android.material.textfield.TextInputLayout;

/**
 * Display a request for a text input a long with a text edit form.
 */
public class StkInputActivity extends AppCompatActivity implements View.OnClickListener,
        TextWatcher {

    // Members
    private int mState;
    private EditText mTextIn = null;
    private TextView mPromptView = null;
    private View mMoreOptions = null;
    private PopupMenu mPopupMenu = null;
    private View mYesNoLayout = null;
    private View mNormalLayout = null;

    // Constants
    private static final String LOG_TAG =
            new Object(){}.getClass().getEnclosingClass().getSimpleName();

    private Input mStkInput = null;
    // Constants
    private static final int STATE_TEXT = 1;
    private static final int STATE_YES_NO = 2;

    static final String YES_STR_RESPONSE = "YES";
    static final String NO_STR_RESPONSE = "NO";

    // Font size factor values.
    static final float NORMAL_FONT_FACTOR = 1;
    static final float LARGE_FONT_FACTOR = 2;
    static final float SMALL_FONT_FACTOR = (1 / 2);

    // Keys for saving the state of the activity in the bundle
    private static final String RESPONSE_SENT_KEY = "response_sent";
    private static final String INPUT_STRING_KEY = "input_string";
    private static final String ALARM_TIME_KEY = "alarm_time";

    private static final String INPUT_ALARM_TAG = LOG_TAG;
    private static final long NO_INPUT_ALARM = -1;
    private long mAlarmTime = NO_INPUT_ALARM;

    private StkAppService appService = StkAppService.getInstance();

    private boolean mIsResponseSent = false;
    private int mSlotId = -1;

    // Click listener to handle buttons press..
    public void onClick(View v) {
        String input = null;
        if (mIsResponseSent) {
            CatLog.d(LOG_TAG, "Already responded");
            return;
        }

        switch (v.getId()) {
        case R.id.button_ok:
            input = mTextIn.getText().toString();
            break;
        case R.id.button_cancel:
            sendResponse(StkAppService.RES_ID_END_SESSION);
            finish();
            return;
        // Yes/No layout buttons.
        case R.id.button_yes:
            input = YES_STR_RESPONSE;
            break;
        case R.id.button_no:
            input = NO_STR_RESPONSE;
            break;
        case R.id.more:
            if (mPopupMenu == null) {
                mPopupMenu = new PopupMenu(this, v);
                Menu menu = mPopupMenu.getMenu();
                createOptionsMenuInternal(menu);
                prepareOptionsMenuInternal(menu);
                mPopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        optionsItemSelectedInternal(item);
                        return true;
                    }
                });
                mPopupMenu.setOnDismissListener(new PopupMenu.OnDismissListener() {
                    public void onDismiss(PopupMenu menu) {
                        mPopupMenu = null;
                    }
                });
                mPopupMenu.show();
            }
            return;
        default:
            break;
        }
        CatLog.d(LOG_TAG, "handleClick, ready to response");
        sendResponse(StkAppService.RES_ID_INPUT, input, false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CatLog.d(LOG_TAG, "onCreate - mIsResponseSent[" + mIsResponseSent + "]");

        // appService can be null if this activity is automatically recreated by the system
        // with the saved instance state right after the phone process is killed.
        if (appService == null) {
            CatLog.d(LOG_TAG, "onCreate - appService is null");
            finish();
            return;
        }

        // Set the layout for this activity.
        setContentView(R.layout.stk_input);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        if (getResources().getBoolean(R.bool.show_menu_title_only_on_menu)) {
            getSupportActionBar().hide();

            mMoreOptions = findViewById(R.id.more);
            mMoreOptions.setVisibility(View.VISIBLE);
            mMoreOptions.setOnClickListener(this);
        }

        // Initialize members
        mTextIn = (EditText) this.findViewById(R.id.in_text);
        mPromptView = (TextView) this.findViewById(R.id.prompt);
        // Set buttons listeners.
        Button okButton = (Button) findViewById(R.id.button_ok);
        Button cancelButton = (Button) findViewById(R.id.button_cancel);
        Button yesButton = (Button) findViewById(R.id.button_yes);
        Button noButton = (Button) findViewById(R.id.button_no);

        okButton.setOnClickListener(this);
        cancelButton.setOnClickListener(this);
        yesButton.setOnClickListener(this);
        noButton.setOnClickListener(this);

        mYesNoLayout = findViewById(R.id.yes_no_layout);
        mNormalLayout = findViewById(R.id.normal_layout);
        initFromIntent(getIntent());
        appService.getStkContext(mSlotId).setPendingActivityInstance(this);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        mTextIn.addTextChangedListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        CatLog.d(LOG_TAG, "onResume - mIsResponseSent[" + mIsResponseSent +
                "], slot id: " + mSlotId);
        if (mAlarmTime == NO_INPUT_ALARM) {
            startTimeOut();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        CatLog.d(LOG_TAG, "onPause - mIsResponseSent[" + mIsResponseSent + "]");
        if (mPopupMenu != null) {
            mPopupMenu.dismiss();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        CatLog.d(LOG_TAG, "onStop - mIsResponseSent[" + mIsResponseSent + "]");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        CatLog.d(LOG_TAG, "onDestroy - before Send End Session mIsResponseSent[" +
                mIsResponseSent + " , " + mSlotId + "]");
        if (appService == null) {
            return;
        }
        // Avoid sending the terminal response while the activty is being restarted
        // due to some kind of configuration change.
        if (!isChangingConfigurations()) {
            // If the input activity is finished by stkappservice
            // when receiving OP_LAUNCH_APP from the other SIM, we can not send TR here,
            // since the input cmd is waiting user to process.
            if (!mIsResponseSent && !appService.isInputPending(mSlotId)) {
                CatLog.d(LOG_TAG, "handleDestroy - Send End Session");
                sendResponse(StkAppService.RES_ID_END_SESSION);
            }
        }
        cancelTimeOut();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mPopupMenu != null) {
            mPopupMenu.dismiss();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mIsResponseSent) {
            CatLog.d(LOG_TAG, "Already responded");
            return true;
        }

        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            CatLog.d(LOG_TAG, "onKeyDown - KEYCODE_BACK");
            sendResponse(StkAppService.RES_ID_BACKWARD, null, false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    void sendResponse(int resId) {
        sendResponse(resId, null, false);
    }

    void sendResponse(int resId, String input, boolean help) {
        cancelTimeOut();

        if (mSlotId == -1) {
            CatLog.d(LOG_TAG, "slot id is invalid");
            return;
        }

        if (StkAppService.getInstance() == null) {
            CatLog.d(LOG_TAG, "StkAppService is null, Ignore response: id is " + resId);
            return;
        }

        if (mMoreOptions != null) {
            mMoreOptions.setVisibility(View.INVISIBLE);
        }

        CatLog.d(LOG_TAG, "sendResponse resID[" + resId + "] input[*****] help[" 
                + help + "]");
        mIsResponseSent = true;
        Bundle args = new Bundle();
        args.putInt(StkAppService.RES_ID, resId);
        if (input != null) {
            args.putString(StkAppService.INPUT, input);
        }
        args.putBoolean(StkAppService.HELP, help);
        appService.sendResponse(args, mSlotId);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        super.onCreateOptionsMenu(menu);
        createOptionsMenuInternal(menu);
        return true;
    }

    private void createOptionsMenuInternal(Menu menu) {
        menu.add(Menu.NONE, StkApp.MENU_ID_END_SESSION, 1, R.string.menu_end_session);
        menu.add(0, StkApp.MENU_ID_HELP, 2, R.string.help);
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        super.onPrepareOptionsMenu(menu);
        prepareOptionsMenuInternal(menu);
        return true;
    }

    private void prepareOptionsMenuInternal(Menu menu) {
        menu.findItem(StkApp.MENU_ID_END_SESSION).setVisible(true);
        menu.findItem(StkApp.MENU_ID_HELP).setVisible(mStkInput.helpAvailable);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (optionsItemSelectedInternal(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean optionsItemSelectedInternal(MenuItem item) {
        if (mIsResponseSent) {
            CatLog.d(LOG_TAG, "Already responded");
            return true;
        }
        switch (item.getItemId()) {
        case StkApp.MENU_ID_END_SESSION:
            sendResponse(StkAppService.RES_ID_END_SESSION);
            finish();
            return true;
        case StkApp.MENU_ID_HELP:
            sendResponse(StkAppService.RES_ID_INPUT, "", true);
            return true;
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        CatLog.d(LOG_TAG, "onSaveInstanceState: " + mSlotId);
        outState.putBoolean(RESPONSE_SENT_KEY, mIsResponseSent);
        outState.putString(INPUT_STRING_KEY, mTextIn.getText().toString());
        outState.putLong(ALARM_TIME_KEY, mAlarmTime);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        CatLog.d(LOG_TAG, "onRestoreInstanceState: " + mSlotId);

        mIsResponseSent = savedInstanceState.getBoolean(RESPONSE_SENT_KEY);
        if (mIsResponseSent && (mMoreOptions != null)) {
            mMoreOptions.setVisibility(View.INVISIBLE);
        }

        String savedString = savedInstanceState.getString(INPUT_STRING_KEY);
        mTextIn.setText(savedString);
        updateButton();

        mAlarmTime = savedInstanceState.getLong(ALARM_TIME_KEY, NO_INPUT_ALARM);
        if (mAlarmTime != NO_INPUT_ALARM) {
            startTimeOut();
        }
    }

    public void beforeTextChanged(CharSequence s, int start, int count,
            int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // Reset timeout.
        cancelTimeOut();
        startTimeOut();
        updateButton();
    }

    public void afterTextChanged(Editable s) {
    }

    private void updateButton() {
        // Disable the button if the length of the input text does not meet the expectation.
        Button okButton = (Button) findViewById(R.id.button_ok);
        okButton.setEnabled((mTextIn.getText().length() < mStkInput.minLen) ? false : true);
    }

    private void cancelTimeOut() {
        if (mAlarmTime != NO_INPUT_ALARM) {
            CatLog.d(LOG_TAG, "cancelTimeOut - slot id: " + mSlotId);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(mAlarmListener);
            mAlarmTime = NO_INPUT_ALARM;
        }
    }

    private void startTimeOut() {
        // No need to set alarm if device sent TERMINAL RESPONSE already.
        if (mIsResponseSent) {
            return;
        }

        if (mAlarmTime == NO_INPUT_ALARM) {
            int duration = StkApp.calculateDurationInMilis(mStkInput.duration);
            if (duration <= 0) {
                duration = StkApp.UI_TIMEOUT;
            }
            mAlarmTime = SystemClock.elapsedRealtime() + duration;
        }

        CatLog.d(LOG_TAG, "startTimeOut: " + mAlarmTime + "ms, slot id: " + mSlotId);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, mAlarmTime, INPUT_ALARM_TAG,
                mAlarmListener, null);
    }

    private void configInputDisplay() {
        TextInputLayout textInput = (TextInputLayout) findViewById(R.id.text_input_layout);

        int inTypeId = R.string.alphabet;

        // set the prompt.
        if ((mStkInput.icon == null || !mStkInput.iconSelfExplanatory)
                && !TextUtils.isEmpty(mStkInput.text)) {
            mPromptView.setText(mStkInput.text);
            mPromptView.setVisibility(View.VISIBLE);
        }

        // Set input type (alphabet/digit) info close to the InText form.
        boolean hideHelper = false;
        if (mStkInput.digitOnly) {
            mTextIn.setKeyListener(StkDigitsKeyListener.getInstance());
            inTypeId = R.string.digits;
            hideHelper = StkAppService.getBooleanCarrierConfig(this,
                    CarrierConfigManager.KEY_HIDE_DIGITS_HELPER_TEXT_ON_STK_INPUT_SCREEN_BOOL,
                    mSlotId);
        }
        textInput.setHelperText(getResources().getString(inTypeId));
        textInput.setHelperTextEnabled(!hideHelper);

        setTitle(R.string.app_name);

        if (mStkInput.icon != null) {
            ImageView imageView = (ImageView) findViewById(R.id.icon);
            imageView.setImageBitmap(mStkInput.icon);
            imageView.setVisibility(View.VISIBLE);
        }

        // Handle specific global and text attributes.
        switch (mState) {
        case STATE_TEXT:
            mTextIn.setFilters(new InputFilter[] {new InputFilter.LengthFilter(mStkInput.maxLen)});

            textInput.setCounterMaxLength(mStkInput.maxLen);
            //do not show the length helper for the text input
            textInput.setCounterEnabled(false);

            if (!mStkInput.echo) {
                mTextIn.setTransformationMethod(PasswordTransformationMethod
                        .getInstance());
            }
            mTextIn.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN);
            // Request the initial focus on the edit box and show the software keyboard.
            mTextIn.requestFocus();
            getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            // Set default text if present.
            if (mStkInput.defaultText != null) {
                mTextIn.setText(mStkInput.defaultText);
            } else {
                // make sure the text is cleared
                mTextIn.setText("", BufferType.EDITABLE);
            }
            updateButton();

            break;
        case STATE_YES_NO:
            // Set display mode - normal / yes-no layout
            mYesNoLayout.setVisibility(View.VISIBLE);
            mNormalLayout.setVisibility(View.GONE);
            break;
        }
    }

    private void initFromIntent(Intent intent) {
        // Get the calling intent type: text/key, and setup the
        // display parameters.
        CatLog.d(LOG_TAG, "initFromIntent - slot id: " + mSlotId);
        if (intent != null) {
            mStkInput = intent.getParcelableExtra("INPUT");
            mSlotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
            CatLog.d(LOG_TAG, "onCreate - slot id: " + mSlotId);
            if (mStkInput == null) {
                finish();
            } else {
                mState = mStkInput.yesNo ? STATE_YES_NO :
                        STATE_TEXT;
                configInputDisplay();
            }
        } else {
            finish();
        }
    }

    private final AlarmManager.OnAlarmListener mAlarmListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    CatLog.d(LOG_TAG, "The alarm time is reached");
                    mAlarmTime = NO_INPUT_ALARM;
                    sendResponse(StkAppService.RES_ID_TIMEOUT);
                }
            };
}
