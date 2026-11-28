import type {Network} from "./sky";
import {layout} from "./sky";

/** Lays out a big sky away from the page, which would otherwise not respond while it takes half a second or more */
self.onmessage = (event: MessageEvent<Network>) => {
  self.postMessage(Array.from(layout(event.data)))
}
