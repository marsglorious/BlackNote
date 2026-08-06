package com.marsglorious.blacknote.ui.list

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.marsglorious.blacknote.ui.theme.MdColors
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text

@Composable
fun BasicTextFieldCompat(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Search,
) {
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = style.copy(color = MdColors.OnSurface),
            cursorBrush = SolidColor(MdColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            modifier = Modifier,
        )
        if (value.isEmpty()) {
            Text(placeholder, style = style.copy(color = MdColors.OnSurfaceFaint))
        }
    }
}
