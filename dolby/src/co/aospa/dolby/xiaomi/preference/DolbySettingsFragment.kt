/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.preference

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragment
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_BASS
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_DIALOGUE
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_DIALOGUE_AMOUNT
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_ENABLE
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_HP_VIRTUALIZER
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_IEQ
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_PRESET
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_PROFILE
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_RESET
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_SPK_VIRTUALIZER
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_STEREO_WIDENING
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_VOLUME
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_VOLUME_AMOUNT
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_VOLUME_MODELER
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_AUDIO_OPTIMIZER
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyController
import co.aospa.dolby.xiaomi.R
import com.android.settingslib.widget.MainSwitchPreference

class DolbySettingsFragment : PreferenceFragment(),
    OnPreferenceChangeListener, CompoundButton.OnCheckedChangeListener {

    private lateinit var switchBar: MainSwitchPreference
    private lateinit var profilePref: ListPreference
    private lateinit var presetPref: Preference
    private lateinit var ieqPref: DolbyIeqPreference
    private lateinit var dialoguePref: SwitchPreferenceCompat
    private lateinit var dialogueAmountPref: SeekBarPreference
    private lateinit var bassPref: SwitchPreferenceCompat
    private lateinit var hpVirtPref: SwitchPreferenceCompat
    private lateinit var spkVirtPref: SwitchPreferenceCompat
    private lateinit var volumePref: SwitchPreferenceCompat
    private lateinit var volumeAmountPref: SeekBarPreference
    private var volumeModelerPref: SwitchPreferenceCompat? = null
    private var audioOptimizerPref: SwitchPreferenceCompat? = null
    private lateinit var resetPref: Preference
    private lateinit var settingsCategory: PreferenceCategory
    private var stereoPref: SeekBarPreference? = null

    private val dolbyController by lazy { DolbyController.getInstance(context) }
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val handler = Handler()

    private var isOnSpeaker = true
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setIsOnSpeaker($value)")
            updateProfileSpecificPrefs()
        }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesAdded")
            updateSpeakerState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesRemoved")
            updateSpeakerState()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        dlog(TAG, "onCreatePreferences")
        addPreferencesFromResource(R.xml.dolby_settings)

        settingsCategory = findPreference<PreferenceCategory>("dolby_category_settings")!!
        switchBar = findPreference<MainSwitchPreference>(PREF_ENABLE)!!
        profilePref = findPreference<ListPreference>(PREF_PROFILE)!!
        presetPref = findPreference<Preference>(PREF_PRESET)!!
        ieqPref = findPreference<DolbyIeqPreference>(PREF_IEQ)!!
        dialoguePref = findPreference<SwitchPreferenceCompat>(PREF_DIALOGUE)!!
        dialogueAmountPref = findPreference<SeekBarPreference>(PREF_DIALOGUE_AMOUNT)!!
        bassPref = findPreference<SwitchPreferenceCompat>(PREF_BASS)!!
        hpVirtPref = findPreference<SwitchPreferenceCompat>(PREF_HP_VIRTUALIZER)!!
        spkVirtPref = findPreference<SwitchPreferenceCompat>(PREF_SPK_VIRTUALIZER)!!
        volumePref = findPreference<SwitchPreferenceCompat>(PREF_VOLUME)!!
        volumeAmountPref = findPreference<SeekBarPreference>(PREF_VOLUME_AMOUNT)!!
        volumeModelerPref = findPreference<SwitchPreferenceCompat>(PREF_VOLUME_MODELER)
        audioOptimizerPref = findPreference<SwitchPreferenceCompat>(PREF_AUDIO_OPTIMIZER)
        resetPref = findPreference<Preference>(PREF_RESET)!!
        stereoPref = findPreference<SeekBarPreference>(PREF_STEREO_WIDENING)

        if (!context.resources.getBoolean(R.bool.dolby_stereo_widening_supported)) {
            stereoPref?.let {
                settingsCategory.removePreference(it)
                stereoPref = null
            }
        }

        val profile = dolbyController.profile
        preferenceManager.preferenceDataStore = DolbyPreferenceStore(context).also {
            it.profile = profile
        }

        val dsOn = dolbyController.dsOn
        switchBar.addOnSwitchChangeListener(this)
        switchBar.isChecked = dsOn

        profilePref.onPreferenceChangeListener = this
        profilePref.isEnabled = dsOn
        profilePref.apply {
            val unknownRes = context.getString(R.string.dolby_unknown)
            if (entryValues.contains(profile.toString())) {
                summary = "%s"
                value = profile.toString()
            } else {
                summary = unknownRes
                dlog(TAG, "current profile $profile unknown")
            }
        }

        hpVirtPref.onPreferenceChangeListener = this
        spkVirtPref.onPreferenceChangeListener = this
        stereoPref?.apply {
            onPreferenceChangeListener = this@DolbySettingsFragment
            min = context.resources.getInteger(R.integer.stereo_widening_min)
            max = context.resources.getInteger(R.integer.stereo_widening_max)
        }
        dialoguePref.onPreferenceChangeListener = this
        dialogueAmountPref.apply {
            onPreferenceChangeListener = this@DolbySettingsFragment
            min = context.resources.getInteger(R.integer.dialogue_enhancer_min)
            max = context.resources.getInteger(R.integer.dialogue_enhancer_max)
        }
        bassPref.onPreferenceChangeListener = this
        volumePref.onPreferenceChangeListener = this
        volumeAmountPref.apply {
            onPreferenceChangeListener = this@DolbySettingsFragment
            min = context.resources.getInteger(R.integer.volume_leveler_min)
            max = context.resources.getInteger(R.integer.volume_leveler_max)
        }
        val volumeModelerSupported = dolbyController.isVolumeModelerSupported()
        volumeModelerPref?.let {
            if (volumeModelerSupported) {
                it.onPreferenceChangeListener = this
            } else {
                it.isVisible = false
            }
        }
        val audioOptimizerSupported = dolbyController.isAudioOptimizerSupported()
        audioOptimizerPref?.let {
            if (audioOptimizerSupported) {
                it.onPreferenceChangeListener = this
            } else {
                it.isVisible = false
            }
        }
        ieqPref.onPreferenceChangeListener = this

        resetPref.setOnPreferenceClickListener {
            dolbyController.resetProfileSpecificSettings()
            updateProfileSpecificPrefs()
            Toast.makeText(
                context,
                context.getString(R.string.dolby_reset_profile_toast, profilePref.summary),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        audioManager!!.registerAudioDeviceCallback(audioDeviceCallback, handler)
        updateSpeakerState()
        updateProfileSpecificPrefs()
    }

    override fun onDestroyView() {
        dlog(TAG, "onDestroyView")
        audioManager!!.unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        updateProfileSpecificPrefs()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        dlog(TAG, "onPreferenceChange: key=${preference.key} value=$newValue")
        when (preference.key) {
            PREF_PROFILE -> {
                val profile = newValue.toString().toInt()
                dolbyController.profile = profile
                (preferenceManager.preferenceDataStore as DolbyPreferenceStore).profile = profile
                updateProfileSpecificPrefs()
            }

            PREF_SPK_VIRTUALIZER -> {
                dolbyController.setSpeakerVirtEnabled(newValue as Boolean)
            }

            PREF_HP_VIRTUALIZER -> {
                dolbyController.setHeadphoneVirtEnabled(newValue as Boolean)
            }

            PREF_STEREO_WIDENING -> {
                dolbyController.setStereoWideningAmount(newValue as Int)
            }

            PREF_DIALOGUE_AMOUNT -> {
                dolbyController.setDialogueEnhancerAmount(newValue as Int)
            }

            PREF_DIALOGUE -> {
                dolbyController.setDialogueEnhancerEnabled(newValue as Boolean)
            }

            PREF_BASS -> {
                dolbyController.setBassEnhancerEnabled(newValue as Boolean)
            }

            PREF_VOLUME -> {
                dolbyController.setVolumeLevelerEnabled(newValue as Boolean)
            }

            PREF_VOLUME_AMOUNT -> {
                dolbyController.setVolumeLevelerAmount(newValue as Int)
            }

            PREF_VOLUME_MODELER -> {
                if (!dolbyController.isVolumeModelerSupported()) return false
                dolbyController.setVolumeModelerEnabled(newValue as Boolean)
            }

            PREF_AUDIO_OPTIMIZER -> {
                if (!dolbyController.isAudioOptimizerSupported()) return false
                dolbyController.setAudioOptimizerEnabled(newValue as Boolean)
            }

            PREF_IEQ -> {
                dolbyController.setIeqPreset(newValue.toString().toInt())
            }

            else -> return false
        }
        return true
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        dlog(TAG, "onCheckedChanged($isChecked)")
        dolbyController.dsOn = isChecked
        profilePref.setEnabled(isChecked)
        updateProfileSpecificPrefs()
    }

    private fun updateSpeakerState() {
        val device = audioManager!!.getDevicesForAttributes(ATTRIBUTES_MEDIA)[0]
        isOnSpeaker = (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    private fun updateProfileSpecificPrefs() {
        val unknownRes = context.getString(R.string.dolby_unknown)
        val headphoneRes = context.getString(R.string.dolby_connect_headphones)
        val dsOn = dolbyController.dsOn
        val currentProfile = dolbyController.profile

        dlog(
            TAG, "updateProfileSpecificPrefs: dsOn=$dsOn currentProfile=$currentProfile"
                    + " isOnSpeaker=$isOnSpeaker"
        )

        val enable = dsOn && (currentProfile != -1)
        presetPref.setEnabled(enable)
        spkVirtPref.setEnabled(enable)
        ieqPref.setEnabled(enable)
        dialoguePref.setEnabled(enable)
        volumePref.setEnabled(enable)
        volumeAmountPref.setEnabled(enable)
        volumeModelerPref?.isEnabled = enable && dolbyController.isVolumeModelerSupported()
        audioOptimizerPref?.isEnabled = enable && dolbyController.isAudioOptimizerSupported()
        resetPref.setEnabled(enable)
        hpVirtPref.setEnabled(enable && !isOnSpeaker)
        stereoPref?.setEnabled(enable && !isOnSpeaker)
        bassPref.setEnabled(enable)

        if (!enable) return

        presetPref.summary = dolbyController.getPresetName()

        val ieqValue = dolbyController.getIeqPreset(currentProfile)
        ieqPref.apply {
            if (entryValues.contains(ieqValue.toString())) {
                summary = "%s"
                value = ieqValue.toString()
            } else {
                summary = unknownRes
                dlog(TAG, "ieq value $ieqValue unknown")
            }
        }

        dialoguePref.isChecked = dolbyController.getDialogueEnhancerEnabled(currentProfile)
        dialogueAmountPref.value = dolbyController.getDialogueEnhancerAmount(currentProfile)

        spkVirtPref.setChecked(dolbyController.getSpeakerVirtEnabled(currentProfile))
        volumePref.setChecked(dolbyController.getVolumeLevelerEnabled(currentProfile))
        volumeAmountPref.value = dolbyController.getVolumeLevelerAmount(currentProfile)
        volumeModelerPref?.isChecked = dolbyController.getVolumeModelerEnabled(currentProfile)
        audioOptimizerPref?.isChecked = dolbyController.getAudioOptimizerEnabled(currentProfile)
        bassPref.setChecked(dolbyController.getBassEnhancerEnabled(currentProfile))

        // below prefs are not enabled on loudspeaker
        if (isOnSpeaker) {
            hpVirtPref.summary = headphoneRes
            return
        }

        stereoPref?.value = dolbyController.getStereoWideningAmount(currentProfile)
        hpVirtPref.apply {
            setChecked(dolbyController.getHeadphoneVirtEnabled(currentProfile))
            summary = null
        }
    }

    companion object {
        private const val TAG = "DolbySettingsFragment"
        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }
}
