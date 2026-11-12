package pl.prosteradio;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lista odcinków: tytuł, data, długość i postęp słuchania. */
public class EpisodeAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final PodcastStore store;
    private final DateFormat dateFormat;
    private List<Episode> items = new ArrayList<>();
    private String playingKey;

    public EpisodeAdapter(LayoutInflater inflater, PodcastStore store, Locale locale) {
        this.inflater = inflater;
        this.store = store;
        this.dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale);
    }

    public void setItems(List<Episode> list) {
        items = list != null ? list : new ArrayList<Episode>();
        notifyDataSetChanged();
    }

    public void setPlaying(Episode e) {
        String k = e != null ? e.key() : null;
        if (k == null ? playingKey != null : !k.equals(playingKey)) {
            playingKey = k;
            notifyDataSetChanged();
        }
    }

    @Override public int getCount() { return items.size(); }
    @Override public Episode getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_episode, parent, false);
            h = new Holder();
            h.title = convertView.findViewById(R.id.ep_title);
            h.meta = convertView.findViewById(R.id.ep_meta);
            h.progress = convertView.findViewById(R.id.ep_progress);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }
        Resources res = convertView.getResources();
        Episode e = items.get(position);
        h.title.setText(e.title);

        boolean done = store.isDone(e);
        long pos = store.position(e);
        StringBuilder meta = new StringBuilder();
        if (e.published > 0) meta.append(dateFormat.format(new Date(e.published)));
        if (e.durationSec > 0) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append(Episode.formatTime(e.durationSec * 1000L));
        }
        if (done) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append(res.getString(R.string.episode_done));
        } else if (pos > 5000) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append(res.getString(R.string.episode_resume, Episode.formatTime(pos)));
        }
        h.meta.setText(meta);

        if (!done && pos > 5000 && e.durationSec > 0) {
            h.progress.setVisibility(View.VISIBLE);
            h.progress.setProgress((int) Math.min(1000, pos / e.durationSec));
        } else {
            h.progress.setVisibility(View.GONE);
        }

        boolean playing = playingKey != null && playingKey.equals(e.key());
        h.title.setTextColor(res.getColor(playing ? R.color.accent : done ? R.color.text_dim : R.color.text));
        return convertView;
    }

    private static final class Holder {
        TextView title, meta;
        ProgressBar progress;
    }
}
