package pl.prosteradio;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Zastępcze „logo”: kolorowy kafelek z pierwszą literą nazwy stacji. */
public class LetterDrawable extends Drawable {
    private static final int[] COLORS = {
            0xFF3D7BD9, 0xFFD9493D, 0xFF3DA35D, 0xFF8E5BD0, 0xFFD98A3D,
            0xFF2E9AA6, 0xFFC2457E, 0xFF6C7A3A, 0xFF5B6BD0, 0xFFA6542E,
    };
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String letter;
    private final RectF rect = new RectF();

    public LetterDrawable(String name) {
        String n = name == null ? "" : name.trim();
        String l = "♪";
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c)) { l = String.valueOf(Character.toUpperCase(c)); break; }
        }
        letter = l;
        int h = n.hashCode();
        bg.setColor(COLORS[(h & 0x7fffffff) % COLORS.length]);
        fg.setColor(0xFFFFFFFF);
        fg.setTextAlign(Paint.Align.CENTER);
        fg.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        float left = b.left + (b.width() - size) / 2f;
        float top = b.top + (b.height() - size) / 2f;
        rect.set(left, top, left + size, top + size);
        float r = size * 0.18f;
        canvas.drawRoundRect(rect, r, r, bg);
        fg.setTextSize(size * 0.48f);
        float y = rect.centerY() - (fg.descent() + fg.ascent()) / 2f;
        canvas.drawText(letter, rect.centerX(), y, fg);
    }

    @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); fg.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { bg.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
