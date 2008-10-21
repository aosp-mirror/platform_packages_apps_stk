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
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import com.android.internal.telephony.gsm.stk.AppInterface;
import com.android.internal.telephony.gsm.stk.FontSize;
import com.android.internal.telephony.gsm.stk.Service;
import com.android.internal.telephony.gsm.stk.TextAttribute;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.text.method.PasswordTransformationMethod;

/**
 * Display a request for a text input a long with a text edit form.
 */
public class StkInputActivity extends Activity implements View.OnClickListener,
        TextWatcher {

    // Members
    private int mState;
    private int mMinTextLength = NO_MIN_LIMIT;
    private EditText mTextIn = null;
    private TextView mPromptView = null;
    private View mYesNoLayout = null;
    private View mNormalLayout = null;
    private WatchDog mTimeoutWatchDog = null;
    private boolean mHelpAvailable = false;

    // Constants
    private static final String TAG = "STK INPUT ACTIVITY";

    private static final int IN_STATE_TEXT = 1;
    private static final int IN_STATE_KEY = 2;

    private static final int NO_MIN_LIMIT = 0;
    private static final int INKEY_MAX_LIMIT = 1;

    private static final String INKEY_MAX_LIMIT_STR = "1";

    // Font size factor values. 
    static final float NORMAL_FONT_FACTOR = 1;
    static final float LARGE_FONT_FACTOR = 2;
    static final float SMALL_FONT_FACTOR = (1 / 2);

    private class Terminate implements Runnable {
        public void run() {
            setResult(StkApp.RESULT_TIMEDOUT);
            finish();
        }
    }

    // Click listener to handle buttons press..
    public void onClick(View v) {
        Intent data = new Intent();

        switch (v.getId()) {
        case R.id.button_ok:
            // Check that text entered is valid .
            if (!verfiyTypedText()) {
                return;
            }
            switch (mState) {
            case IN_STATE_TEXT:
                // return input text to the calling activity
                String input = mTextIn.getText().toString();
                data.putExtra(Util.INPUT_TYPE_TEXT, input);
                break;
            case IN_STATE_KEY:
                // return input key to the calling activity
                Character key = new Character(mTextIn.getText().toString()
                        .charAt(0));
                data.putExtra(Util.INPUT_TYPE_KEY, key.charValue());
                break;
            }
            break;
        // Yes/No layout buttons.
        case R.id.button_yes:
            onYesNoButtonClick(true);
            break;
        case R.id.button_no:
            onYesNoButtonClick(true);
            break;
        }

        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Set the layout for this activity.
        setContentView(R.layout.stk_input);

        // Initialize members
        mTextIn = (EditText) this.findViewById(R.id.in_text);
        mPromptView = (TextView) this.findViewById(R.id.prompt);

        // Set buttons listeners.
        Button okButton = (Button) findViewById(R.id.button_ok);     
        Button yesButton = (Button) findViewById(R.id.button_yes);
        Button noButton = (Button) findViewById(R.id.button_no);

        okButton.setOnClickListener(this);
        yesButton.setOnClickListener(this);
        noButton.setOnClickListener(this);

        mYesNoLayout = findViewById(R.id.yes_no_layout);
        mNormalLayout = findViewById(R.id.normal_layout);

        // Get the calling intent type: text/key, and setup the 
        // display parameters.
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            // set input state
            String str = extras.getString(Util.INPUT_TYPE);
            mState = (str.equals(Util.INPUT_TYPE_TEXT)) ? IN_STATE_TEXT
                    : IN_STATE_KEY;
            configInputDisplay(extras);
        }
        // Create a watch dog to terminate the activity if no input is received
        // after one minute.
        mTimeoutWatchDog = new WatchDog(null, new Handler(), new Terminate(),
                StkApp.UI_TIMEOUT);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        mTextIn.addTextChangedListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTimeoutWatchDog.cancel();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        AppInterface stkService = Service.getInstance();

        // Reset timeout.
        mTimeoutWatchDog.reset();

        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            setResult(StkApp.RESULT_BACKWARD);
            finish();
            break;
        case KeyEvent.KEYCODE_HOME:
            setResult(StkApp.RESULT_END_SESSION);
            finish();
            break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        // Reset timeout.
        mTimeoutWatchDog.reset();
        return super.onTrackballEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Reset timeout.
        mTimeoutWatchDog.reset();
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, StkApp.MENU_ID_MAIN, 1, R.string.sim_main_menu);
        menu.add(0, StkApp.MENU_ID_HELP, 2, R.string.help);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(StkApp.MENU_ID_MAIN).setVisible(true);
        menu.findItem(StkApp.MENU_ID_HELP).setVisible(mHelpAvailable);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        AppInterface stkService = Service.getInstance();

        switch (item.getItemId()) {
        case StkApp.MENU_ID_MAIN:
            setResult(StkApp.RESULT_END_SESSION);
            finish();
            return true;
        case StkApp.MENU_ID_HELP:
            setResult(StkApp.RESULT_HELP);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void beforeTextChanged(CharSequence s, int start, int count,
            int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // Reset timeout.
        mTimeoutWatchDog.reset();
    }

    public void afterTextChanged(Editable s) {
    }

    private void onYesNoButtonClick(boolean yesNo) {
        Intent data = new Intent();
        data.putExtra(Util.INPUT_TYPE_KEY, yesNo);

        // End the activity.
        setResult(StkApp.RESULT_OK, data);
        finish();
    }

    private boolean verfiyTypedText() {
        // If not enough input was typed in stay on the edit screen.
        if (mTextIn.getText().length() < mMinTextLength) return false;

        return true;
    }

    private void configInputDisplay(Bundle extras) {
        TextView numOfCharsView = (TextView) findViewById(R.id.num_of_chars);
        TextView inTypeView = (TextView) findViewById(R.id.input_type);
        Bundle bundle = extras.getBundle(Util.INPUT_TEXT_ATTRS);
        Bundle glblAttrs = extras.getBundle(Util.INPUT_GLBL_ATTRS);
        TextAttribute textAttrs = null;
        int inTypeId = R.string.alphabet;

        // set the prompt.
        String prompt = extras.getString(Util.INPUT_PROMPT);
        if (prompt != null) {
            mPromptView.setText(prompt);
        }

        // Unpack text attributes from the bundle.
        if (bundle != null) textAttrs = Util.unPackTextAttr(bundle);

        // Set input type (alphabet/digit) info close to the InText form. 
        if (glblAttrs.getBoolean(Util.INPUT_ATTR_DIGITS)) {
            mTextIn.setKeyListener(StkDigitsKeyListener.getInstance());
            inTypeId = R.string.digits;
        } 
        inTypeView.setText(inTypeId);

        if (glblAttrs.getBoolean(Util.INPUT_ATTR_HELP)) {
            mHelpAvailable = true;
        }

        // Handle specific global and text attributes.
        switch (mState) {
        case IN_STATE_TEXT:
            // Handle text attributes setup for get input.
            if (textAttrs != null) {
                // Set font size.
                float size = mPromptView.getTextSize() * 
                                getFontSizeFactor(textAttrs.size);

                mPromptView.setTextSize(size);

                // Set prompt to bold.
                if (textAttrs.bold) {
                    mPromptView.setTypeface(Typeface.DEFAULT_BOLD);
                }
                // Set prompt to italic.
                if (textAttrs.italic) {
                    mPromptView.setTypeface(Typeface.create(Typeface.DEFAULT,
                            Typeface.ITALIC));
                }
                // Set text color.
                mPromptView.setTextColor(textAttrs.color.ordinal());
            }
            // Handle global attributes setup.
            mMinTextLength = glblAttrs.getInt(Util.INPUT_ATTR_MINLEN);

            // Set the maximum number of characters according to the maximum 
            // input size.
            int maxTextLength = glblAttrs.getInt(Util.INPUT_ATTR_MAXLEN);
            mTextIn.setFilters(new InputFilter[] {new InputFilter.LengthFilter(
                    maxTextLength)});
 
            // Set number of chars info.
            String lengthLimit = String.valueOf(mMinTextLength);
            if (maxTextLength != mMinTextLength) {
                lengthLimit = mMinTextLength + " - " + maxTextLength;
            }
            numOfCharsView.setText(lengthLimit);

            if (!glblAttrs.getBoolean(Util.INPUT_ATTR_ECHO)) {
                mTextIn.setTransformationMethod(PasswordTransformationMethod
                        .getInstance());
            }
            // Set default text if present.
            String defaultText = extras.getString(Util.INPUT_DEFAULT);
            if (defaultText != null) {
                mTextIn.setText(defaultText);
            }

            break;
        case IN_STATE_KEY:
            // Set display mode - normal / yes-no layout
            if (glblAttrs.getBoolean(Util.INPUT_ATTR_YES_NO)) {
                mYesNoLayout.setVisibility(View.VISIBLE);
                mNormalLayout.setVisibility(View.GONE);
                break;
            }
            // In case of a input key, limit the text in to a single char.
            mTextIn.setFilters(new InputFilter[] {new InputFilter.LengthFilter(
                    INKEY_MAX_LIMIT)});
            mMinTextLength = INKEY_MAX_LIMIT;
            numOfCharsView.setText(INKEY_MAX_LIMIT_STR);
            break;
        }
    }

    private float getFontSizeFactor(FontSize size) {
        final float[] fontSizes = 
            {NORMAL_FONT_FACTOR, LARGE_FONT_FACTOR, SMALL_FONT_FACTOR};

        return fontSizes[size.ordinal()];
    }
}
