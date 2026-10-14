package com.example.twitchnotifier

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onRemove: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<Channel>()

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.chAvatar)
        val name: TextView = v.findViewById(R.id.chName)
        val remove: TextView = v.findViewById(R.id.chRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.displayName
        if (ch.avatarUrl.isNotEmpty()) {
            holder.avatar.load(ch.avatarUrl) {
                crossfade(true)
                placeholder(android.R.drawable.sym_def_app_icon)
                error(android.R.drawable.sym_def_app_icon)
            }
        } else {
            holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon)
        }
        holder.remove.setOnClickListener { onRemove(ch) }
    }

    override fun getItemCount() = items.size
}
