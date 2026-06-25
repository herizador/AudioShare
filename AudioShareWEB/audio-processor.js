const RING_SIZE = 131072; // 3s a 44100 Hz
const MIN_FILL = 4096;    // ~93ms de buffer antes de emitir

class AudioProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.smoothing = 0.85;
    this.smoothedLevel = 0;
    this.ring = new Float32Array(RING_SIZE);
    this.rp = 0; // read pointer
    this.wp = 0; // write pointer
    this.started = false;

    this.port.onmessage = (ev) => {
      if (ev.data.type === "reset") {
        this.rp = this.wp = 0;
        this.started = false;
        return;
      }
      if (ev.data.type === "samples") {
        const src = ev.data.samples;
        for (let i = 0; i < src.length; i++) {
          this.ring[this.wp] = src[i];
          this.wp = (this.wp + 1) & (RING_SIZE - 1);
        }
        if (!this.started && this.available() >= MIN_FILL) {
          this.started = true;
        }
      }
    };
  }

  available() {
    return (this.wp - this.rp) & (RING_SIZE - 1);
  }

  process(inputs, outputs) {
    const output = outputs[0];
    if (!output || !output[0]) return true;
    const outChan = output[0];
    const avail = this.available();
    let sumSq = 0;

    if (!this.started || avail < outChan.length) {
      for (let i = 0; i < outChan.length; i++) {
        outChan[i] = 0;
      }
      this.port.postMessage({ type: "level", value: 0 });
      return true;
    }

    for (let i = 0; i < outChan.length; i++) {
      const s = this.ring[this.rp];
      this.rp = (this.rp + 1) & (RING_SIZE - 1);
      outChan[i] = s;
      sumSq += s * s;
    }

    const rms = Math.sqrt(sumSq / outChan.length);
    this.smoothedLevel =
      this.smoothedLevel * this.smoothing + rms * (1 - this.smoothing);

    this.port.postMessage({ type: "level", value: this.smoothedLevel });
    return true;
  }
}

registerProcessor("audio-processor", AudioProcessor);
