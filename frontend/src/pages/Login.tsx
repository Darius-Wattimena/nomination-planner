import {useEffect, useRef} from "react";
import {useNavigate, useSearchParams} from "react-router";
import {login} from "../resources/client";
import {Loading} from "../ui/States";
import {getSession} from "../utils/session";
import {finishLogin} from "../utils/login";

export default function Login() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const started = useRef(false)

  useEffect(() => {
    // The code of osu! can only be used once
    if (started.current) return
    started.current = true

    if (getSession()?.user) {
      navigate("/", {replace: true})
      return
    }

    const code = params.get("code")
    const returnTo = finishLogin(params.get("state"))
    const fail = () => navigate("/", {replace: true, state: {loginFailed: true}})

    if (!code || !returnTo) {
      fail()
      return
    }

    login(code)
      .then(() => getSession()?.user ? navigate(returnTo, {replace: true}) : fail())
      .catch(fail)
  }, [navigate, params])

  return (
    <div className="welcome">
      <h1>Logging in</h1>
      <p className="muted">Please wait while we validate your osu! account.</p>
      <Loading/>
    </div>
  )
}
