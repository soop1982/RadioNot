package pl.prosteradio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ładuje logotypy stacji w tle, z pamięcią podręczną w RAM i na dysku. */
public class ImageLoader {
    private final Context ctx;
    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<String>());
    private final File dir;
    private final int sizePx;

    public ImageLoader(Context ctx, int sizePx) {
        this.ctx = ctx.getApplicationContext();
        this.sizePx = sizePx;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mem = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
        dir = new File(ctx.getCacheDir(), "logos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void load(final String url, final String name, final ImageView iv) {
        iv.setTag(url);
        if (url == null || url.length() == 0 || failed.contains(url)) {
            iv.setImageDrawable(new LetterDrawable(name));
            return;
        }
        Bitmap cached = mem.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(new LetterDrawable(name));
        if (!pending.add(url)) {
            // już się pobiera – po zakończeniu odświeżymy ten widok
            waitAndSet(url, iv, 0);
            return;
        }
        pool.execute(() -> {
            Bitmap bmp = fetch(url);
            pending.remove(url);
            if (bmp == null) {
                failed.add(url);
                return;
            }
            mem.put(url, bmp);
            main.post(() -> {
                if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    private void waitAndSet(final String url, final ImageView iv, final int tries) {
        main.postDelayed(() -> {
            if (!url.equals(iv.getTag())) return;
            Bitmap b = mem.get(url);
            if (b != null) iv.setImageBitmap(b);
            else if (pending.contains(url) && tries < 40) waitAndSet(url, iv, tries + 1);
        }, 250);
    }

    private Bitmap fetch(String url) {
        File f = new File(dir, md5(url));
        try {
            byte[] data;
            if (f.exists() && f.length() > 0) {
                data = readFile(f);
            } else {
                data = Http.get(ctx, url, 1024 * 1024);
                FileOutputStream out = new FileOutputStream(f);
                out.write(data);
                out.close();
            }
            Bitmap b = decode(data);
            if (b == null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    private Bitmap decode(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= sizePx && o.outHeight / (sample * 2) >= sizePx) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        return b;
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
