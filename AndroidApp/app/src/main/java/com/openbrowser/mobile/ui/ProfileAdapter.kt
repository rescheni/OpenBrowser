package com.openbrowser.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.DevicePresets
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.databinding.ItemProfileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileAdapter(
    private val onOpen: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onMore: (Profile, View) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.ProfileHolder>() {

    private val items = mutableListOf<Profile>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submit(list: List<Profile>) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].id == list[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos] == list[newPos]
        })
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        Motion.pressScale(binding.card)
        return ProfileHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ProfileHolder(private val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: Profile) {
            val context = binding.root.context
            val name = profile.name.ifBlank { context.getString(R.string.empty_title) }
            binding.tvName.text = name
            binding.tvInitial.text = name.trim().firstOrNull()?.uppercase()?.toString() ?: "?"

            val proxyLabel = if (profile.proxy.enabled) profile.proxy.display() else context.getString(R.string.profile_direct)
            binding.tvBadge.text = proxyLabel
            binding.tvBadge.setBackgroundResource(
                if (profile.proxy.enabled) R.drawable.bg_chip_accent else R.drawable.bg_chip,
            )
            binding.tvBadge.setTextColor(
                context.getColor(
                    if (profile.proxy.enabled) R.color.deck_accent else R.color.deck_text_dim,
                ),
            )

            val group = profile.group.ifBlank { context.getString(R.string.profile_ungrouped) }
            binding.tvGroup.text = group
            binding.tvLastUsed.text = if (profile.lastUsedAt > 0) {
                context.getString(
                    R.string.profile_last_used,
                    dateFormat.format(Date(profile.lastUsedAt)),
                )
            } else {
                context.getString(R.string.profile_last_used_never)
            }

            val preset = DevicePresets.match(profile)
            val device = profile.deviceModel.ifBlank {
                preset?.title ?: if (profile.mobile) {
                    context.getString(R.string.profile_mobile_device)
                } else {
                    context.getString(R.string.profile_desktop_device)
                }
            }

            val dpr = profile.devicePixelRatio.toString().trimEnd('0').trimEnd('.')
            binding.tvSummary.text = buildString {
                append(device)
                append(" · ")
                append(profile.screenWidth)
                append("×")
                append(profile.screenHeight)
                append(" · DPR ")
                append(dpr)
                append(" · ")
                append(profile.locale)
                append(" · ")
                append(profile.timezone)
            }

            binding.card.setOnClickListener { onOpen(profile) }
            binding.btnOpen.setOnClickListener { onOpen(profile) }
            binding.btnEdit.setOnClickListener { onEdit(profile) }
            binding.btnMore.setOnClickListener { onMore(profile, it) }
            binding.card.setOnLongClickListener {
                onMore(profile, binding.btnMore)
                true
            }
        }
    }
}
