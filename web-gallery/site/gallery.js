"use strict";

const start = document.querySelector("#start");
const status = document.querySelector("#status");
const reload = document.querySelector("#reload");
reload.addEventListener("click", () => location.reload());

start.addEventListener("click", async () => {
  start.disabled = true;
  const started = performance.now();
  try {
    status.textContent = "Loading Java 11…";
    if (typeof cheerpjInit !== "function") throw new Error("The CheerpJ runtime could not load. Check your connection and reload.");
    const response = await fetch("./manifest.json");
    if (!response.ok) throw new Error("The gallery build manifest could not load.");
    const manifest = await response.json();
    const classPath = manifest.classpath.map(path => "/app" + new URL(path, document.baseURI).pathname).join(":");
    if (manifest.sourceCommit) document.querySelector("#build").textContent = "Build " + manifest.sourceCommit.slice(0, 7);
    await cheerpjInit({
      version: 11,
      status: "default",
      javaProperties: ["user.home=/files/gallery", "flatlaf.useNativeLibrary=false"],
      overrideShortcuts: event => event.target.closest?.("#display") != null
        && ["Home", "End", "PageUp", "PageDown", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", " "].includes(event.key),
      natives: {
        Java_com_flippingutilities_ui_uiutilities_BrowserGallery_ready: () => {
          window.galleryReadyMs = Math.round(performance.now() - started);
          status.textContent = "Ready · Choose a state below. Export PNG downloads an image.";
          start.textContent = "Gallery running";
          reload.hidden = false;
        }
      }
    });
    cheerpjCreateDisplay(-1, -1, document.querySelector("#display"));
    status.textContent = "Opening Swing components…";
    window.galleryStartedAt = started;
    const exitCode = await cheerpjRunMain(manifest.mainClass, classPath, ...manifest.args);
    status.textContent = exitCode === 0 ? "Gallery closed. Reload the page to open it again." : "The gallery exited with code " + exitCode + ".";
    reload.hidden = false;
  } catch (error) {
    status.textContent = error.message || String(error);
    reload.hidden = false;
    console.error(error);
  }
});
