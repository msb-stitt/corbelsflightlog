// Adds a version switcher, filled from versions.json at the site root.
// Every published version writes that file, so older pages list newer ones.
(function () {
  var here = window.location.pathname;
  var root = here.slice(0, here.indexOf("/", 1) + 1) === "/" ? "/" : here.replace(/\/[^\/]*\/.*$/, "/");
  fetch(root + "versions.json")
    .then(function (r) { return r.json(); })
    .then(function (data) {
      var sidebar = document.querySelector(".sidebar-brand") || document.body;
      var select = document.createElement("select");
      select.style.cssText = "margin-top:0.75rem;width:100%";
      data.versions.forEach(function (v) {
        var o = document.createElement("option");
        o.value = root + v + "/";
        o.textContent = v;
        if (here.indexOf("/" + v + "/") !== -1) o.selected = true;
        select.appendChild(o);
      });
      select.addEventListener("change", function () { window.location.href = select.value; });
      sidebar.appendChild(select);
    })
    .catch(function () { /* no versions.json yet: no switcher */ });
})();
