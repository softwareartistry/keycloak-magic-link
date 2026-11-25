<#-- Disables the #kc-resend button, counting down, until the per-user email send cooldown has
     passed. A resend before then is skipped server-side, so this tells the user to wait instead of
     letting the click silently do nothing. The timer runs for every visitor, account or not, so it
     reveals nothing about which addresses have accounts. -->
<#if resendCooldownSeconds?? && resendCooldownSeconds gt 0>
<script>
  (function () {
    var button = document.getElementById("kc-resend");
    if (!button) return;
    var isInput = button.tagName === "INPUT";
    var label = isInput ? button.value : button.textContent.trim();
    var left = ${resendCooldownSeconds?c};
    function render() {
      var text = left > 0 ? label + " (" + left + "s)" : label;
      if (isInput) { button.value = text; } else { button.textContent = text; }
      button.disabled = left > 0;
    }
    render();
    var timer = setInterval(function () {
      left--;
      render();
      if (left <= 0) clearInterval(timer);
    }, 1000);
  })();
</script>
</#if>
