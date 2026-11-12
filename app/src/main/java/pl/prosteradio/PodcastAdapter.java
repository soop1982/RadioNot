package pl.prosteradio;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Lista podcastów z nagłówkami sekcji („Subskrybowane”, „Ranking…”). */
public class PodcastAdapter extends BaseAdapter {
    public interface OnStarListener {
        void onStar(Podcast p);
    }

    /** Wiersz listy: nagłówek (text) albo podcast. */
    public static final class Row {
        public final String header;
        public final Podcast podcast;
        public final boolean clickableHeader;

        private Row(String header, Podcast podcast, boolean clickableHeader) {
            this.header = header;
            this.podcast = podcast;
            this.clickableHeader = clickableHeader;
        }

        public static Row header(String text) { return new Row(text, null, false); }
        /** Nagłówek, który można dotknąć (np. „spróbuj ponownie”). */
        public static Row action(String text) { return new Row(text, null, true); }
        public static Row of(Podcast p) { return new Row(null, p, false); }
    }

    private final LayoutInflater inflater;
    private final ImageLoader images;
    private final PodcastStore store;
    private final OnStarListener starListener;
    private List<Row> rows = new ArrayList<>();

    public PodcastAdapter(LayoutInflater inflater, ImageLoader images, PodcastStore store, OnStarListener l) {
        this.inflater = inflater;
        this.images = images;
        this.store = store;
        this.starListener = l;
    }

    public void setRows(List<Row> r) {
        rows = r != null ? r : new ArrayList<Row>();
        notifyDataSetChanged();
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return rows.get(position).podcast == null ? 0 : 1; }

    @Override
    public boolean isEnabled(int position) {
        Row r = rows.get(position);
        return r.podcast != null || r.clickableHeader;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row r = rows.get(position);
        if (r.podcast == null) {
            if (convertView == null) convertView = inflater.inflate(R.layout.item_header, parent, false);
            TextView t = (TextView) convertView;
            t.setText(r.header);
            t.setTextColor(convertView.getResources().getColor(r.clickableHeader ? R.color.error : R.color.accent));
            return convertView;
        }
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
        final Podcast p = r.podcast;
        h.name.setText(p.name);
        h.details.setText(p.author);
        h.details.setVisibility(p.author.length() > 0 ? View.VISIBLE : View.GONE);
        images.load(p.artwork, p.name, h.logo);
        h.star.setImageResource(store.isSubscribed(p)
                ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
        h.star.setOnClickListener(v -> starListener.onStar(p));
        return convertView;
    }

    private static final class Holder {
        ImageView logo;
        TextView name, details;
        ImageButton star;
    }
}
