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

import android.os.Bundle;
import com.android.internal.telephony.gsm.stk.FontSize;
import com.android.internal.telephony.gsm.stk.TextAlignment;
import com.android.internal.telephony.gsm.stk.TextAttribute;
import com.android.internal.telephony.gsm.stk.TextColor;

public class Util {
    // Constants
    public static final String INPUT_TYPE                   = "Stk.InputType";
    public static final String INPUT_PROMPT                 = "Stk.InputPrompt";
    public static final String INPUT_DEFAULT                = "Stk.InputDefault";
    public static final String INPUT_CALL_PERMISSION        = "Stk.InputCallPermission";
    public static final String INPUT_TYPE_TEXT              = "Stk.InputType.Text";
    public static final String INPUT_TYPE_KEY               = "Stk.InputType.Key";
    
    public static final String INPUT_TEXT_ATTRS             = "Stk.TextAtrs";
    public static final String INPUT_ATTR_START             = "Stk.Atr.Start";
    public static final String INPUT_ATTR_LENGHT            = "Stk.Atr.Length";
    public static final String INPUT_ATTR_ALIGN             = "Stk.Atr.Align";
    public static final String INPUT_ATTR_SIZE              = "Stk.Atr.Size";
    public static final String INPUT_ATTR_BOLD              = "Stk.Atr.Bold'";
    public static final String INPUT_ATTR_ITALIC            = "Stk.Atr.Italic";
    public static final String INPUT_ATTR_UNDERLINED        = "Stk.Atr.underlined'";    
    public static final String INPUT_ATTR_STRIKE_THROUGH    = "Stk.Atr.Strike.Through";
    public static final String INPUT_ATTR_COLOR             = "Stk.Atr.Color";

    public static final String INPUT_GLBL_ATTRS             = "Stk.GlblAtrs";
    public static final String INPUT_ATTR_MINLEN            = "Stk.Atr.MinLen";
    public static final String INPUT_ATTR_MAXLEN            = "Stk.Atr.MaxLen";
    public static final String INPUT_ATTR_NOMAAXLIM         = "Stk.Atr.NoMaxLimit";
    public static final String INPUT_ATTR_DIGITS            = "Stk.Atr.Digits";
    public static final String INPUT_ATTR_UCS2              = "Stk.Atr.UCS2";
    public static final String INPUT_ATTR_ECHO              = "Stk.Atr.Echo";
    public static final String INPUT_ATTR_HELP              = "Stk.Atr.Help";
    public static final String INPUT_ATTR_IMD_RESPONSE      = "Stk.Atr.ImdResponse";
    public static final String INPUT_ATTR_YES_NO            = "Stk.Atr.YesNo";

    // Packing text attributes into a bundle to pass with an Intent.
    public static Bundle packTextAttr(TextAttribute textAttrs) {
        if (textAttrs == null) return null;

        Bundle bundle = new Bundle();

        bundle.putInt(INPUT_ATTR_START, textAttrs.start);
        bundle.putInt(INPUT_ATTR_LENGHT, textAttrs.length);
        if (textAttrs.align != null) {
            bundle.putInt(INPUT_ATTR_ALIGN, textAttrs.align.ordinal());
        }
        if (textAttrs.size != null) {
            bundle.putInt(INPUT_ATTR_SIZE, textAttrs.size.ordinal());
        }
        bundle.putBoolean(INPUT_ATTR_BOLD, textAttrs.bold);
        bundle.putBoolean(INPUT_ATTR_ITALIC, textAttrs.italic);
        bundle.putBoolean(INPUT_ATTR_UNDERLINED, textAttrs.underlined);
        bundle.putBoolean(INPUT_ATTR_STRIKE_THROUGH, textAttrs.strikeThrough);
        if (textAttrs.color != null) {
            bundle.putInt(INPUT_ATTR_COLOR, textAttrs.color.ordinal());
        }
        return bundle;
    }

    // Unpacking text attributes from a bundle passed with an Intent.
    public static TextAttribute unPackTextAttr(Bundle bundle) {
        if (bundle == null) return null;
         
        int start = bundle.getInt(INPUT_ATTR_START);
        int length = bundle.getInt(INPUT_ATTR_LENGHT);
        TextAlignment align = TextAlignment.fromInt(bundle.getInt(INPUT_ATTR_ALIGN));
        FontSize size = FontSize.fromInt(bundle.getInt(INPUT_ATTR_SIZE));
        boolean bold = bundle.getBoolean(INPUT_ATTR_BOLD);
        boolean italic = bundle.getBoolean(INPUT_ATTR_ITALIC);
        boolean underlined = bundle.getBoolean(INPUT_ATTR_UNDERLINED);
        boolean strikeThrough = bundle.getBoolean(INPUT_ATTR_STRIKE_THROUGH);
        TextColor color = TextColor.fromInt(bundle.getInt(INPUT_ATTR_COLOR));
        
        TextAttribute textAttrs = new TextAttribute(start, length, align, size,
                bold, italic, underlined, strikeThrough, color);
        
        return textAttrs;
    }
}





