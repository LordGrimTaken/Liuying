(function(){var els=document.querySelectorAll('.reveal');
if(!('IntersectionObserver' in window)||matchMedia('(prefers-reduced-motion: reduce)').matches){els.forEach(function(e){e.classList.add('in')});return}
var io=new IntersectionObserver(function(es){es.forEach(function(e){if(e.isIntersecting){e.target.classList.add('in');io.unobserve(e.target)}})},{threshold:.12});
els.forEach(function(e){io.observe(e)})})();
/* nav: swap "Sign in" for the username when a session exists */
(function(){
  var slot=document.getElementById('nav-auth'),hero=document.getElementById('hero-signin');
  if(!slot||!window.fetch)return;
  fetch('/api/auth/me',{credentials:'same-origin',headers:{Accept:'application/json'}}).then(function(r){
    return r.status===200?r.json():null}).then(function(d){
    if(!d||!d.user)return;
    var a=slot.querySelector('a');a.textContent=d.user.username;a.href='/account';a.classList.add('me');
    a.setAttribute('aria-label','Your account: '+d.user.username);
    if(hero){hero.textContent='Your account';hero.href='/account'}
  }).catch(function(){});
})();
