/*
min-ppl.scala

SMC-based probability monad for bootstrap particle filter inference

 */

object MinPpl2 {

  import breeze.stats.{distributions => bdist}
  import breeze.linalg.DenseVector
  import cats._
  import cats.implicits._

  implicit val numParticles = 2000

  case class Particle[T](v: T, lw: Double) { // value and log-weight
    def map[S](f: T => S): Particle[S] = Particle(f(v), lw)
    def flatMap[S](f: T => Particle[S]): Particle[S] = {
      val ps = f(v)
      Particle(ps.v, lw + ps.lw)
    }
  }

  implicit val particleMonad = new Monad[Particle] {
    def pure[T](t: T): Particle[T] = Particle(t, 0.0)
    def flatMap[T,S](pt: Particle[T])(f: T => Particle[S]): Particle[S] = pt.flatMap(f)
    def tailRecM[T,S](t: T)(f: T => Particle[Either[T,S]]): Particle[S] = ???
  }

  trait Prob[T] {
    val particles: Vector[Particle[T]]
    def draw: Particle[T]
    def mapP[S](f: T => Particle[S]): Prob[S] = Empirical(particles map (_ flatMap f))
    def map[S](f: T => S): Prob[S] = mapP(v => Particle(f(v), 0.0))
    def flatMap[S](f: T => Prob[S]): Prob[S] = mapP(f(_).draw)
    def resample(implicit N: Int): Prob[T] = {
      val lw = particles map (_.lw)
      val mx = lw reduce (math.max(_,_))
      val rw = lw map (lwi => math.exp(lwi - mx))
      val law = mx + math.log(rw.sum/(rw.length))
      val ind = bdist.Multinomial(DenseVector(rw.toArray)).sample(N)
      val newParticles = ind map (i => particles(i))
      Empirical(newParticles.toVector map (pi => Particle(pi.v, law)))
    }
    def cond(ll: T => Double): Prob[T] = mapP(v => Particle(v, ll(v)))
    def empirical: Vector[T] = resample.particles.map(_.v)
  }

  implicit val probMonad = new Monad[Prob] {
    def pure[T](t: T): Prob[T] = Empirical(Vector(Particle(t, 0.0)))
    def flatMap[T,S](pt: Prob[T])(f: T => Prob[S]): Prob[S] = pt.flatMap(f)
    def tailRecM[T,S](t: T)(f: T => Prob[Either[T,S]]): Prob[S] = ???
  }

  case class Empirical[T](particles: Vector[Particle[T]]) extends Prob[T] {
    def draw: Particle[T] = {
      val lw = particles map (_.lw)
      val mx = lw reduce (math.max(_,_))
      val rw = lw map (lwi => math.exp(lwi - mx))
      val law = mx + math.log(rw.sum/(rw.length))
      val idx = bdist.Multinomial(DenseVector(rw.toArray)).draw
      Particle(particles(idx).v, law)
    }
  }

  def unweighted[T](ts: Vector[T], lw: Double = 0.0): Prob[T] =
    Empirical(ts map (Particle(_, lw)))

  trait Dist[T] extends Prob[T] {
    def ll(obs: T): Double
    def ll(obs: Seq[T]): Double = obs map (ll) reduce (_+_)
    def fit(obs: Seq[T]): Prob[T] = mapP(v => Particle(v, ll(obs)))
    def fitQ(obs: Seq[T]): Prob[T] = Empirical(Vector(Particle(obs.head, ll(obs))))
    def fit(obs: T): Prob[T] = fit(List(obs))
    def fitQ(obs: T): Prob[T] = fitQ(List(obs))
  }

  case class Normal(mu: Double, v: Double)(implicit N: Int) extends Dist[Double] {
    lazy val particles = unweighted(bdist.Gaussian(mu, math.sqrt(v)).
      sample(N).toVector).particles
    def draw = Particle(bdist.Gaussian(mu, math.sqrt(v)).draw, 0.0)
    def ll(obs: Double) = bdist.Gaussian(mu, math.sqrt(v)).logPdf(obs)
  }

  case class Gamma(a: Double, b: Double)(implicit N: Int) extends Dist[Double] {
    lazy val particles = unweighted(bdist.Gamma(a, 1.0/b).
      sample(N).toVector).particles
    def draw = Particle(bdist.Gamma(a, 1.0/b).draw, 0.0)
    def ll(obs: Double) = bdist.Gamma(a, 1.0/b).logPdf(obs)
  }

  case class Poisson(mu: Double)(implicit N: Int) extends Dist[Int] {
    lazy val particles = unweighted(bdist.Poisson(mu).
      sample(N).toVector).particles
    def draw = Particle(bdist.Poisson(mu).draw, 0.0)
    def ll(obs: Int) = bdist.Poisson(mu).logProbabilityOf(obs)
  }

}


// eof

