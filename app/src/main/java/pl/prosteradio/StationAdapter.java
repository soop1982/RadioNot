package pl.prosteradio;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class StationAdapter extends BaseAdapter {
    public interface OnStarListener {
        void onStar(Station s);
    }

    private final LayoutInflater inflater;
    private final ImageLoader images;
    private final Favorites favorites;
    private final OnStarListener starListener;
    private List<Station> items = new ArrayList<>();
    private String playingKey;

    public StationAdapter(LayoutInflater inflater, ImageLoader images, Favorites favorites, OnStarListener l) {
        this.inflater = inflater;
        this.images = images;
        this.favorites = favorites;
        this.starListener = l;
    }

    public void setItems(List<Station> list) {
        items = list != null ? list : new ArrayList<Station>();
        notifyDataSetChanged();
    }

    public void setPlaying(Station s) {
        String k = s != null ? s.key() : null;
        if (k == null ? playingKey != null : !k.equals(playingKey)) {
            playingKey = k;
            notifyDataSetChanged();
        }
    }

    @Override public int getCount() { return items.size(); }
    @Override public Station getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_station, parent, false);
            h = new Holder();
            h.logo = convertView.findViewById(R.id.logo);
            h.name = convertView.findViewById(R.id.name);
            h.details = convertView.findViewById(R.id.details);
            h.star = convertView.findViewById(R.id.star);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }
        final Station s = items.get(position);
        Resources res = convertView.getResources();
        h.name.setText(s.name);
        boolean unsupported = s.isUnsupportedHere();
        String d;
        if (unsupported) {
            // stacja w formacie, którego to urządzenie nie odtworzy – wyszarzona z wyjaśnieniem
            d = res.getString(R.string.not_supported_opus);
            h.details.setTextColor(res.getColor(R.color.error));
        } else {
            d = s.custom ? res.getString(R.string.custom_station) + "  ·  " + s.shortUrl() : s.details();
            h.details.setTextColor(res.getColor(R.color.text_dim));
        }
        h.details.setText(d);
        h.details.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
        images.load(s.favicon, s.name, h.logo);
        float alpha = unsupported ? 0.45f : 1f;
        h.logo.setAlpha(alpha);
        h.name.setAlpha(alpha);
        boolean fav = favorites.contains(s);
        h.star.setImageResource(fav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
        h.star.setOnClickListener(v -> starListener.onStar(s));
        boolean playing = playingKey != null && playingKey.equals(s.key());
        h.name.setTextColor(res.getColor(playing ? R.color.accent : R.color.text));
        return convertView;
    }

    private static final class Holder {
        ImageView logo;
        TextView name, details;
        ImageButton star;
    }
}
