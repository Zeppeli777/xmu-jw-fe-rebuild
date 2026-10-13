import { boot } from "./app.js";
import { renderXywccx } from "./xywccx.js";
import { renderJdfpm } from "./jdfpm.js";
import { renderZmsq } from "./zmsq.js";

boot({
  xywccx: renderXywccx,
  jdfpm: renderJdfpm,
  zmsq: renderZmsq,
});
