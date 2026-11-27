import {useCallback, useEffect, useRef, useState} from "react";
import {previewUrl} from "../utils/preview";
import {readText, storeText} from "../utils/storage";

function storedVolume() {
  // A plain number, also as stored before there were versions
  const volume = Number(readText("volume") ?? 0.35)
  return volume >= 0 && volume <= 1 ? volume : 0.35
}

/** Start playing, telling when the browser refuses until someone touches the page */
function start(player: HTMLAudioElement, onRefused: () => void) {
  player.play().catch(error => {
    // Changing to another beatmap also interrupts playing, that is not a failure
    if (error?.name === "NotAllowedError") onRefused()
  })
}

/** Plays the preview of the shown beatmap. Once started, the next beatmaps start by themselves. */
export function useSongPreview(id: string | undefined, nextId: string | undefined) {
  const audio = useRef<HTMLAudioElement>(null)
  const wanted = useRef(false)
  const [playing, setPlaying] = useState(false)
  const [missing, setMissing] = useState(false)
  const [volume, setVolume] = useState(storedVolume)

  function element() {
    audio.current ??= new Audio()
    return audio.current
  }

  // Playing on its own is refused until someone touches the page
  const refused = useCallback(() => {
    wanted.current = false
    setPlaying(false)
  }, [])

  function play() {
    start(element(), refused)
  }

  // A new song starts at the beginning, and might exist after all
  const [songFor, setSongFor] = useState(id)
  if (songFor !== id) {
    setSongFor(id)
    setMissing(false)
  }

  useEffect(() => {
    const player = element()
    player.loop = true
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onError = () => {
      if (!player.getAttribute("src")) return
      setMissing(true)
      setPlaying(false)
    }
    player.addEventListener("play", onPlay)
    player.addEventListener("pause", onPause)
    player.addEventListener("error", onError)
    return () => {
      player.removeEventListener("play", onPlay)
      player.removeEventListener("pause", onPause)
      player.removeEventListener("error", onError)
      player.pause()
      player.removeAttribute("src")
      player.load()
    }
  }, [])

  useEffect(() => {
    const player = element()
    player.pause()
    if (!id) {
      player.removeAttribute("src")
      return
    }
    player.preload = "none"
    player.src = previewUrl(id)
    if (wanted.current) start(player, refused)
  }, [id, refused])

  // Have the next song ready, but only for those who are listening
  useEffect(() => {
    if (!nextId || !playing) return
    const next = new Audio()
    next.preload = "auto"
    next.src = previewUrl(nextId)
    return () => {
      next.removeAttribute("src")
    }
  }, [nextId, playing])

  useEffect(() => {
    element().volume = volume
    storeText("volume", String(volume))
  }, [volume])

  // Only the play button follows how far the song is, so the rest of the page doesn't render on every step
  const onProgress = useCallback((listener: (progress: number) => void) => {
    const player = audio.current ??= new Audio()
    const onTime = () => listener(player.duration > 0 ? player.currentTime / player.duration : 0)
    player.addEventListener("timeupdate", onTime)
    return () => player.removeEventListener("timeupdate", onTime)
  }, [])

  return {
    playing, onProgress, missing, volume, setVolume,
    toggle: () => {
      wanted.current = !playing
      if (playing) element().pause()
      else play()
    }
  }
}
