const menuButton = document.querySelector('.menu-button');
const sidebar = document.querySelector('#sidebar');

if (menuButton && sidebar) {
  menuButton.addEventListener('click', () => {
    const open = sidebar.classList.toggle('open');
    menuButton.setAttribute('aria-expanded', String(open));
  });

  sidebar.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => {
      sidebar.classList.remove('open');
      menuButton.setAttribute('aria-expanded', 'false');
    });
  });
}

const links = [...document.querySelectorAll('.toc-link')];
const sections = links
  .map((link) => document.querySelector(link.getAttribute('href')))
  .filter(Boolean);

const observer = new IntersectionObserver((entries) => {
  const visible = entries
    .filter((entry) => entry.isIntersecting)
    .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
  if (!visible) return;
  links.forEach((link) => link.classList.toggle('active', link.getAttribute('href') === `#${visible.target.id}`));
}, { rootMargin: '-12% 0px -72% 0px', threshold: 0 });

sections.forEach((section) => observer.observe(section));
