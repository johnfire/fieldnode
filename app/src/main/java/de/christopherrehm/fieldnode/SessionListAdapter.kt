package de.christopherrehm.fieldnode

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.christopherrehm.fieldnode.session.SessionMeta

/**
 * Session-list rows for the agent drawer. Tap a row to switch; long-press for rename/delete. The current
 * session is marked with a leading dot. Mirrors the thin debug-UI style of the rest of the app.
 */
class SessionListAdapter(
    private var items: List<SessionMeta>,
    private var currentId: String,
    private val onSelect: (SessionMeta) -> Unit,
    private val onLongPress: (SessionMeta) -> Unit,
) : RecyclerView.Adapter<SessionListAdapter.Holder>() {

    fun submit(items: List<SessionMeta>, currentId: String) {
        this.items = items
        this.currentId = currentId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_session, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val meta = items[position]
        val isCurrent = meta.id == currentId
        holder.title.text = if (isCurrent) "● ${meta.title}" else meta.title
        holder.meta.text = DateUtils.getRelativeTimeSpanString(meta.updatedAt)
        holder.itemView.setOnClickListener { onSelect(meta) }
        holder.itemView.setOnLongClickListener { onLongPress(meta); true }
    }

    override fun getItemCount(): Int = items.size

    class Holder(row: View) : RecyclerView.ViewHolder(row) {
        val title: TextView = row.findViewById(R.id.session_title)
        val meta: TextView = row.findViewById(R.id.session_meta)
    }
}
