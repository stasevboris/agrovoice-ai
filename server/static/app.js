"use strict";
let currentMap;

function renderMap() {
  const host = document.getElementById("map");
  const data = document.getElementById("map-data");
  if (!host || !data || typeof L === "undefined") return;
  if (currentMap) currentMap.remove();
  currentMap = L.map(host, {attributionControl: true}).setView([53.9, 27.56], 6);
  const points = [];
  const exportLink = document.getElementById("export-link");
  if (exportLink) exportLink.href = "/export.xlsx" + window.location.search;
  for (const row of JSON.parse(data.dataset.points)) {
    if (typeof row.lat !== "number" || typeof row.lon !== "number") continue;
    const point = [row.lat, row.lon];
    const link = document.createElement("a");
    link.href = row.path;
    link.textContent = `${row.label} · ${row.plot}`;
    L.circleMarker(point, {radius: 7, color: "#235f40", fillColor: "#69a771", fillOpacity: .8})
      .addTo(currentMap).bindPopup(link);
    points.push(point);
  }
  if (points.length) currentMap.fitBounds(points, {maxZoom: 15, padding: [25, 25]});
  document.getElementById("enable-tiles").addEventListener("click", event => {
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    }).addTo(currentMap);
    event.currentTarget.disabled = true;
    event.currentTarget.textContent = "Подложка включена";
  }, {once: true});
}

document.addEventListener("DOMContentLoaded", renderMap);
document.addEventListener("htmx:afterSettle", renderMap);
