// Page switching only. Wire your backend calls into the "TODO" spots.
const NAV = {
  pub:   [['home','Home'],['how','How it works'],['about','About'],['login-admin','Admin Log In']],
  donor: [['donor-home','Dashboard'],['donor-browse','Browse Requests'],['donor-match','Find a Match'],['donor-donations','My Donations']],
  org:   [['org-home','Dashboard'],['org-requests','My Requests'],['org-confirm','Pending Confirmation']],
  admin: [['admin-home','Dashboard'],['admin-verify','Verifications'],['admin-accounts','Accounts']]
};
const ROLE = { donor:'Donor', org:'Organization', admin:'Admin' };
const LOGO = '<a class="brand" href="#/home"><img data-img="/images/Logo-top" alt="Damayan"></a>';

// Images are written as data-img="/images/name" (no extension). We try each extension until one loads.
const EXTS = ['png', 'jpg', 'jpeg', 'webp', 'svg'];
function loadImgs() {
  document.querySelectorAll('img[data-img]').forEach(img => {
    if (img.dataset.done) return;
    img.dataset.done = '1';
    let i = 0;
    img.onerror = () => {
      i++;
      if (i < EXTS.length) img.src = `${img.dataset.img}.${EXTS[i]}`;
      else img.onerror = null;
    };
    img.src = `${img.dataset.img}.${EXTS[0]}`;
  });
}

const link = ([id, t], cur) => `<a href="#/${id}" class="${id === cur ? 'on' : ''}">${t}</a>`;

function render() {
  const name = location.hash.replace('#/','') || 'home';
  const view = document.querySelector(`.view[data-v="${name}"]`) || document.querySelector('.view[data-v="home"]');
  document.querySelectorAll('.view').forEach(v => v.classList.toggle('on', v === view));

  const role = view.dataset.role;           // pub | auth | donor | org | admin
  const cur = view.dataset.nav || view.dataset.v;
  let right = '';
  if (role === 'auth') right = '<a href="#/home">← Back to home</a>';
  else if (role === 'pub') right = `<nav>${NAV.pub.map(l => link(l, cur)).join('')}</nav>`;
  else right = `<nav>${NAV[role].map(l => link(l, cur)).join('')}</nav>
    <div class="who">Logged in as: ${ROLE[role]} <a class="btn sm" href="#/home">Log Out</a></div>`;

  document.getElementById('top').innerHTML = LOGO + right;
  document.getElementById('foot').hidden = role !== 'pub';
  loadImgs();
  window.scrollTo(0, 0);
}

// Filter pills: click to highlight one. TODO: call your backend here and re-render the table body.
document.addEventListener('click', e => {
  const p = e.target.closest('.pill');
  if (p) {
    p.parentElement.querySelectorAll('.pill').forEach(x => x.classList.remove('on'));
    p.classList.add('on');
  }
});

// Forms: stop the page reloading. TODO: fetch('/api/...') with the form data.
document.addEventListener('submit', e => e.preventDefault());

addEventListener('hashchange', render);
render();