import {lazy, ReactNode, Suspense} from "react";
import {Link, Route, Routes, useLocation} from 'react-router-dom';
import PageHeader from "./ui/PageHeader";
import Shell from "./ui/Shell";
import {EmptyState, Loading, LoginButton} from "./ui/States";
import {useSession} from "./utils/session";

const Home = lazy(() => import("./pages/Home"));
const Login = lazy(() => import("./pages/Login"));
const Beatmaps = lazy(() => import("./pages/Beatmaps"));
const Sync = lazy(() => import("./pages/Sync"));

export default function AppRoutes() {
  return (
    <Suspense fallback={<Loading/>}>
      <Routes>
        <Route element={<Shell/>}>
          <Route path="/" element={<Home/>}/>
          <Route path="/login" element={<Login/>}/>
          <Route path="/beatmaps" element={<RequireAuth><Beatmaps key="PENDING" page="PENDING"/></RequireAuth>}/>
          <Route path="/graveyard" element={<RequireAuth><Beatmaps key="GRAVEYARD" page="GRAVEYARD"/></RequireAuth>}/>
          <Route path="/ranked" element={<RequireAuth><Beatmaps key="RANKED" page="RANKED"/></RequireAuth>}/>
          <Route path="/admin" element={<RequireAuth><Sync/></RequireAuth>}/>
          <Route path="*" element={<NotFound/>}/>
        </Route>
      </Routes>
    </Suspense>
  )
}

function RequireAuth({children}: {children: ReactNode}) {
  const session = useSession()
  const location = useLocation()

  if (session?.user) return <>{children}</>

  return (
    <EmptyState title="Log in to continue">
      <p className="muted">This page is only available to logged-in osu! users.</p>
      <LoginButton returnTo={location.pathname + location.search}/>
    </EmptyState>
  )
}

function NotFound() {
  return (
    <>
      <PageHeader title="Page not found"/>
      <EmptyState title="There is nothing here">
        <p className="muted">The link may be old, or the page has moved.</p>
        <Link to="/" className="button button-primary">Back to home</Link>
      </EmptyState>
    </>
  )
}
