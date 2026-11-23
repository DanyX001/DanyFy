import Foundation
import AVFoundation
import MediaPlayer
import DanyFyShared

public class IosAudioEngine: NSObject, PlatformAudio {
    private var player: AVPlayer?
    private var timeObserverToken: Any?
    private var currentTrackId: String = ""
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentArtworkUrl: String?
    private var currentDurationMs: Int64 = 0

    public override init() {
        super.init()
        setupAudioSession()
        setupRemoteCommands()
        setupNotifications()
    }

    deinit {
        removeTimeObserver()
        NotificationCenter.default.removeObserver(self)
    }

    private func setupAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            print("Failed to configure AVAudioSession: \(error)")
        }
    }

    public func playStream(
        url: String,
        headers: [String : String],
        trackId: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationMs: Int64
    ) {
        guard let streamUrl = URL(string: url) else {
            PlatformRegistry.shared.audioCallbacks?.onPlaybackError(message: "Invalid stream URL")
            return
        }

        self.currentTrackId = trackId
        self.currentTitle = title
        self.currentArtist = artist
        self.currentAlbum = album
        self.currentArtworkUrl = artworkUrl
        self.currentDurationMs = durationMs

        removeTimeObserver()
        player?.pause()

        var options: [String: Any] = [:]
        if !headers.isEmpty {
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
        }
        let asset = AVURLAsset(url: streamUrl, options: options)
        let playerItem = AVPlayerItem(asset: asset)

        if player == nil {
            player = AVPlayer(playerItem: playerItem)
        } else {
            player?.replaceCurrentItem(with: playerItem)
        }

        setupTimeObserver()
        updateNowPlayingInfo(positionSeconds: 0, durationSeconds: Double(durationMs) / 1000.0)

        PlatformRegistry.shared.audioCallbacks?.onPlaybackStateChanged(isPlaying: true, isBuffering: true)
        player?.play()
    }

    public func pause() {
        player?.pause()
        PlatformRegistry.shared.audioCallbacks?.onPlaybackStateChanged(isPlaying: false, isBuffering: false)
        updatePlaybackRate(rate: 0.0)
    }

    public func resume() {
        player?.play()
        PlatformRegistry.shared.audioCallbacks?.onPlaybackStateChanged(isPlaying: true, isBuffering: false)
        updatePlaybackRate(rate: 1.0)
    }

    public func seekTo(positionMs: Int64) {
        let time = CMTime(seconds: Double(positionMs) / 1000.0, preferredTimescale: 600)
        player?.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            self?.updateNowPlayingPosition()
        }
    }

    public func stop() {
        removeTimeObserver()
        player?.pause()
        player = nil
        PlatformRegistry.shared.audioCallbacks?.onPlaybackStateChanged(isPlaying: false, isBuffering: false)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func setupTimeObserver() {
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserverToken = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self = self else { return }
            let positionMs = Int64(time.seconds * 1000.0)
            let itemDuration = self.player?.currentItem?.duration.seconds ?? 0
            let durationMs = (itemDuration > 0 && !itemDuration.isNaN) ? Int64(itemDuration * 1000.0) : self.currentDurationMs

            let isBuffering = (self.player?.timeControlStatus == .waitingToPlayAtSpecifiedRate)
            let isPlaying = (self.player?.timeControlStatus == .playing)

            PlatformRegistry.shared.audioCallbacks?.onPlaybackStateChanged(isPlaying: isPlaying, isBuffering: isBuffering)
            PlatformRegistry.shared.audioCallbacks?.onPositionDiscontinuity(positionMs: positionMs, durationMs: durationMs)
        }
    }

    private func removeTimeObserver() {
        if let token = timeObserverToken {
            player?.removeTimeObserver(token)
            timeObserverToken = nil
        }
    }

    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleItemDidPlayToEnd),
            name: .AVPlayerItemDidPlayToEndTime,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleItemFailedToPlayToEnd),
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    @objc private func handleItemDidPlayToEnd(notification: Notification) {
        PlatformRegistry.shared.audioCallbacks?.onTrackEnded()
    }

    @objc private func handleItemFailedToPlayToEnd(notification: Notification) {
        PlatformRegistry.shared.audioCallbacks?.onPlaybackError(message: "Playback failed to play to end")
    }

    @objc private func handleAudioInterruption(notification: Notification) {
        guard let info = notification.userInfo,
              let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

        if type == .began {
            pause()
        } else if type == .ended {
            if let optionsValue = info[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    resume()
                }
            }
        }
    }

    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.resume()
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            if self?.player?.timeControlStatus == .playing {
                self?.pause()
            } else {
                self?.resume()
            }
            return .success
        }
        commandCenter.nextTrackCommand.addTarget { _ in
            PlatformRegistry.shared.audioCallbacks?.onTrackEnded()
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.seekTo(positionMs: 0)
            return .success
        }
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seekTo(positionMs: Int64(posEvent.positionTime * 1000.0))
            return .success
        }
    }

    private func updateNowPlayingInfo(positionSeconds: Double, durationSeconds: Double) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: currentTitle,
            MPMediaItemPropertyArtist: currentArtist,
            MPMediaItemPropertyAlbumTitle: currentAlbum,
            MPMediaItemPropertyPlaybackDuration: durationSeconds,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: positionSeconds,
            MPNowPlayingInfoPropertyPlaybackRate: 1.0
        ]

        if let artUrlString = currentArtworkUrl, let artUrl = URL(string: artUrlString) {
            URLSession.shared.dataTask(with: artUrl) { data, _, _ in
                if let d = data, let image = UIImage(data: d) {
                    DispatchQueue.main.async {
                        var updated = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                        let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                        updated[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = updated
                    }
                }
            }.resume()
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func updatePlaybackRate(rate: Double) {
        if var info = MPNowPlayingInfoCenter.default().nowPlayingInfo {
            info[MPNowPlayingInfoPropertyPlaybackRate] = rate
            if let currentSeconds = player?.currentTime().seconds {
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentSeconds
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    private func updateNowPlayingPosition() {
        if var info = MPNowPlayingInfoCenter.default().nowPlayingInfo,
           let currentSeconds = player?.currentTime().seconds {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentSeconds
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}
