import {useEffect} from "react";
import {useNavigate, useSearchParams} from "react-router";
import {Loading} from "../ui/States";

/** Where the backend sends the browser when logging in with osu! didn't work out */
export default function Login() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const failed = params.has("failed")

  useEffect(() => {
    navigate("/", {replace: true, state: {loginFailed: failed}})
  }, [navigate, failed])

  return <Loading/>
}
