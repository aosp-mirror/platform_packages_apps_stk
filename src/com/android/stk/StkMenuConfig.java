/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.util.XmlUtils;

import java.util.ArrayList;

/**
 * Provides preset label and/or icon in accordance with mcc/mnc
 * conbination of the inserted SIM card for Multi-SIM model.
 */
public class StkMenuConfig {
    private static final String LOG_TAG = "StkMenuConfig";
    private static final boolean DBG = Build.IS_DEBUGGABLE;

    private static final String XML_OPERATORS_TAG = "operators";
    private static final String XML_OPERATOR_TAG = "operator";

    private static final String XML_MCC_ATTR = "mcc";
    private static final String XML_MNC_ATTR = "mnc";
    private static final String XML_LABEL_ATTR = "label";
    private static final String XML_ICON_ATTR = "icon";
    private static final String RESOURCE_TYPE = "drawable";

    private static final int UNSPECIFIED = -1;

    private static final Config NO_CONFIG = new Config(0, 0, null, null);

    private static final Object sLock = new Object();
    private static StkMenuConfig sInstance;

    private Context mContext;
    private ArrayList<Config> mArray;
    private Config mConfigs[] = null;

    private static class Config {
        public int mcc;
        public int mnc;
        public String label;
        public String icon;

        public Config(int mcc, int mnc, String label, String icon) {
            this.mcc = mcc;
            this.mnc = mnc;
            this.label = label;
            this.icon = icon;
        }
    }

    public static StkMenuConfig getInstance(Context applicationContext) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new StkMenuConfig();
                sInstance.initialize(applicationContext);
            }
            return sInstance;
        }
    }

    /**
     * Returns a preset label, if exists.
     */
    public String getLabel(int slotId) {
        findConfig(slotId);

        if (DBG) CatLog.d(LOG_TAG, "getLabel: " + mConfigs[slotId].label + ", slot id: " + slotId);
        return mConfigs[slotId].label;
    }

    /**
     * Returns a preset icon, if exists.
     */
    public Bitmap getIcon(int slotId) {
        findConfig(slotId);

        Bitmap bitmap = null;
        if (mConfigs[slotId].icon != null) {
            int resId = mContext.getResources().getIdentifier(mConfigs[slotId].icon,
                    RESOURCE_TYPE, mContext.getPackageName());
            bitmap = resId == UNSPECIFIED ? null :
                    BitmapFactory.decodeResource(mContext.getResources(), resId);
        }
        if (DBG) CatLog.d(LOG_TAG, "getIcon: " + mConfigs[slotId].icon + ", slot id: " + slotId);
        return bitmap;
    }

    private void findConfig(int slotId) {
        int[] subId = SubscriptionManager.getSubId(slotId);
        if (subId == null) {
            mConfigs[slotId] = NO_CONFIG;
            return;
        }

        TelephonyManager telephony =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        String operator = telephony.getSimOperator(subId[0]);
        if (TextUtils.isEmpty(operator) || (operator.length() < 5)) {
            mConfigs[slotId] = NO_CONFIG;
            return;
        }

        int mcc = Integer.parseInt(operator.substring(0, 3));
        int mnc = Integer.parseInt(operator.substring(3));

        if (mConfigs[slotId] != null && mConfigs[slotId].mcc == mcc
                && mConfigs[slotId].mnc == mnc) {
            if (DBG) CatLog.d(LOG_TAG, "Return the cached config, slot id: " + slotId);
            return;
        }

        if (DBG) CatLog.d(LOG_TAG, "Find config and create the cached config, slot id: " + slotId);
        for (Config config : mArray) {
            if ((config.mcc == mcc) && (config.mnc == mnc)) {
                mConfigs[slotId] = config;
                return;
            }
        }

        mConfigs[slotId] = new Config(mcc, mnc, null, null);
    }

    private void initialize(Context context) {
        mContext = context;
        mArray = new ArrayList<Config>();
        mConfigs = new Config[TelephonyManager.from(mContext).getSimCount()];

        XmlResourceParser parser = mContext.getResources().getXml(R.xml.menu_conf);

        try {
            XmlUtils.beginDocument(parser, XML_OPERATORS_TAG);

            do {
                XmlUtils.nextElement(parser);

                if (!XML_OPERATOR_TAG.equals(parser.getName())) {
                    break;
                }

                int mcc = parser.getAttributeIntValue(null, XML_MCC_ATTR, UNSPECIFIED);
                int mnc = parser.getAttributeIntValue(null, XML_MNC_ATTR, UNSPECIFIED);

                if ((mcc == UNSPECIFIED) || (mnc == UNSPECIFIED)) {
                    continue;
                }

                String label = parser.getAttributeValue(null, XML_LABEL_ATTR);
                String icon = parser.getAttributeValue(null, XML_ICON_ATTR);

                Config config = new Config(mcc, mnc, label, icon);
                mArray.add(config);
            } while (true);
        } catch (Exception e) {
            CatLog.e(LOG_TAG, "Something wrong happened while interpreting the xml file" + e);
        } finally {
            parser.close();
        }
    }
}
