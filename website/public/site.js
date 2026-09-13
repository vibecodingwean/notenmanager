const dialog = document.querySelector('#media-dialog');
const content = document.querySelector('#media-content');
function clearMedia() {
  const video = content.querySelector('video');
  if (video) {
    video.pause();
    video.onloadedmetadata = null;
    video.ontimeupdate = null;
    video.removeAttribute('src');
    video.load();
  }
  content.replaceChildren();
}
function closeMedia() { clearMedia(); dialog.close(); }
document.querySelector('.close').onclick = closeMedia;
dialog.addEventListener('cancel', clearMedia);
dialog.addEventListener('close', () => { if (!dialog.open) clearMedia(); });
document.querySelectorAll('[data-start]').forEach(button => button.onclick = () => {
  clearMedia();
  document.querySelector('#media-title').textContent = button.dataset.title;
  const video = document.createElement('video');
  video.controls = true;
  video.playsInline = true;
  video.preload = 'metadata';
  const track = document.createElement('track');
  track.kind = 'subtitles';
  track.srclang = 'de';
  track.label = 'Deutsch';
  track.src = 'assets/captions-de.vtt';
  video.append(track);
  video.onloadedmetadata = () => {
    video.currentTime = Number(button.dataset.start);
    video.play().catch(() => {});
  };
  if (Number(button.dataset.end) > 0) video.ontimeupdate = () => {
    if (video.currentTime >= Number(button.dataset.end)) video.pause();
  };
  video.src = 'assets/tutorial-v2.mp4';
  content.replaceChildren(video);
  dialog.showModal();
});
document.querySelectorAll('[data-image]').forEach(link => link.onclick = event => {
  event.preventDefault();
  clearMedia();
  document.querySelector('#media-title').textContent = 'App-Aufnahme';
  const image = document.createElement('img');
  image.src = link.href;
  image.alt = link.querySelector('img').alt;
  content.replaceChildren(image);
  dialog.showModal();
});
dialog.onclick = event => { if (event.target === dialog) closeMedia(); };
